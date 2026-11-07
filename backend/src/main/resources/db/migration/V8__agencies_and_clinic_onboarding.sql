-- ADR-026 and ADR-028: partner agencies over isolated clinics, clinics that wait for their first
-- owner, and a narrower runtime role. Agencies never read clinic data: they only appear here, in
-- the control database, and never in tenant_memberships.

CREATE TABLE agencies (
    agency_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    -- Idempotency key of the platform command that created the agency.
    created_request_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agency_memberships (
    agency_id UUID NOT NULL REFERENCES agencies(agency_id) ON DELETE RESTRICT,
    subject_id UUID NOT NULL REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    role VARCHAR(30) NOT NULL CHECK (role IN ('AGENCY_ADMIN')),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agency_id, subject_id)
);
CREATE INDEX agency_memberships_subject_idx ON agency_memberships(subject_id) WHERE active;

CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER agencies_touch BEFORE UPDATE ON agencies
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER agency_memberships_touch BEFORE UPDATE ON agency_memberships
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- A clinic belongs to at most one agency; NULL is a direct clinic. agency_request_id is the
-- idempotency key of "create clinic" within one agency: the same key in another agency is a
-- different request and reveals nothing.
ALTER TABLE tenants
    ADD COLUMN agency_id UUID REFERENCES agencies(agency_id) ON DELETE RESTRICT,
    ADD COLUMN agency_request_id UUID,
    ADD CONSTRAINT tenants_agency_request_unique UNIQUE (agency_id, agency_request_id),
    ADD CONSTRAINT tenants_agency_request_needs_agency
        CHECK (agency_request_id IS NULL OR agency_id IS NOT NULL);
CREATE INDEX tenants_agency_portfolio_idx ON tenants(agency_id, clinic_name, tenant_id)
    WHERE agency_id IS NOT NULL;

-- AWAITING_OWNER: provisioned, but nobody accepted the owner invitation yet. Same pattern as V6:
-- no IF EXISTS, so an unexpected constraint name fails instead of leaving two CHECKs.
ALTER TABLE tenants DROP CONSTRAINT tenants_status_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_status_check
    CHECK (status IN ('PROVISIONING', 'AWAITING_OWNER', 'ACTIVE', 'SUSPENDED', 'FAILED'));

-- Allowed status transitions, and agency fields immutable until leaving an agency is decided
-- (ADR-026). Inserts are not covered: the runtime cannot choose the status (see grants below).
CREATE FUNCTION guard_tenant_lifecycle() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.agency_id IS DISTINCT FROM OLD.agency_id
        OR NEW.agency_request_id IS DISTINCT FROM OLD.agency_request_id THEN
        RAISE EXCEPTION 'The agency of a clinic is immutable' USING ERRCODE = 'BRL02';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'PROVISIONING' AND NEW.status IN ('AWAITING_OWNER', 'ACTIVE', 'FAILED'))
        OR (OLD.status = 'FAILED' AND NEW.status = 'PROVISIONING')
        OR (OLD.status = 'AWAITING_OWNER' AND NEW.status IN ('ACTIVE', 'SUSPENDED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'SUSPENDED')
        -- Never SUSPENDED to AWAITING_OWNER: activate_awaiting_clinic would then lift a
        -- suspension without the platform deciding it.
        OR (OLD.status = 'SUSPENDED' AND NEW.status = 'ACTIVE')) THEN
        RAISE EXCEPTION 'Clinic status cannot change from % to %', OLD.status, NEW.status
            USING ERRCODE = 'BRL02';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenants_lifecycle_guard BEFORE UPDATE ON tenants
FOR EACH ROW EXECUTE FUNCTION guard_tenant_lifecycle();

-- Without an admin, the provisioner leaves the clinic AWAITING_OWNER (or ACTIVE, when the owner
-- invitation was accepted during provisioning). The path with an admin stays for CRM-014.
ALTER TABLE tenant_provisioning_tasks ALTER COLUMN admin_subject_id DROP NOT NULL;

-- The only way the API activates a clinic: AWAITING_OWNER to ACTIVE, nothing else. The V6 trigger
-- still requires an owner. SECURITY DEFINER so the runtime needs no UPDATE on tenants.status.
CREATE FUNCTION activate_awaiting_clinic(clinic UUID) RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = clinic AND status = 'AWAITING_OWNER';
    RETURN FOUND;
END;
$$;
REVOKE EXECUTE ON FUNCTION activate_awaiting_clinic(UUID) FROM PUBLIC;

-- Narrow the broad V4 grants (ADR-028). The runtime keeps reading and keeps UPDATE on updated_at
-- only: row locks (FOR NO KEY UPDATE) need UPDATE on at least one column, the V2 trigger rewrites
-- updated_at anyway, and the lifecycle trigger refuses every status or agency change. Creating
-- clinics (INSERT by columns, never status) comes with the agency service that validates the
-- agency; writes to user_accounts and memberships come back column by column with the invitation
-- flow. Tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        REVOKE INSERT, UPDATE, DELETE ON tenants FROM crm_control_runtime;
        GRANT UPDATE (updated_at) ON tenants TO crm_control_runtime;
        REVOKE INSERT, UPDATE, DELETE ON user_accounts FROM crm_control_runtime;
        REVOKE INSERT ON tenant_memberships FROM crm_control_runtime;
        GRANT SELECT ON agencies, agency_memberships TO crm_control_runtime;
        GRANT EXECUTE ON FUNCTION activate_awaiting_clinic(UUID) TO crm_control_runtime;
    END IF;
END;
$$;
