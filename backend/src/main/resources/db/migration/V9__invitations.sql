-- ADR-028: one invitation table for clinic members (including the first owner, issued by an
-- agency) and agency admins. Only the SHA-256 of the token is stored; the raw token exists in
-- memory and in the e-mail link, never in the database or logs.
CREATE TABLE invitations (
    invitation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    -- Same rule as user_accounts.email: normalized by the service before reaching SQL.
    email VARCHAR(254) NOT NULL CHECK (email = lower(btrim(email)) AND email LIKE '%_@_%._%'),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('CLINIC', 'AGENCY')),
    tenant_id UUID REFERENCES tenants(tenant_id) ON DELETE RESTRICT,
    agency_id UUID REFERENCES agencies(agency_id) ON DELETE RESTRICT,
    -- Clinic roles repeat the ADR-025 catalog of tenant_memberships: change both together.
    role VARCHAR(30) NOT NULL,
    -- NULL when the platform command issued it.
    invited_by UUID REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    -- Set only on the first-owner invitation an agency issues for a clinic of its portfolio.
    issuer_agency_id UUID REFERENCES agencies(agency_id) ON DELETE RESTRICT,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    accepted_by UUID REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    send_count INTEGER NOT NULL DEFAULT 0 CHECK (send_count >= 0),
    last_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (kind = 'CLINIC' AND tenant_id IS NOT NULL AND agency_id IS NULL
            AND role IN ('OWNER', 'MANAGER', 'ATTENDANT', 'VIEWER'))
        OR (kind = 'AGENCY' AND agency_id IS NOT NULL AND tenant_id IS NULL
            AND role = 'AGENCY_ADMIN')),
    CHECK (issuer_agency_id IS NULL OR (kind = 'CLINIC' AND role = 'OWNER')),
    CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CHECK (expires_at > created_at)
);

-- Pending = neither accepted nor revoked. An expired, pending invitation is reissued by rotating
-- the same row, so expiry never needs to be part of the index.
CREATE UNIQUE INDEX invitations_pending_clinic_email ON invitations(tenant_id, email)
    WHERE kind = 'CLINIC' AND accepted_at IS NULL AND revoked_at IS NULL;
CREATE UNIQUE INDEX invitations_pending_agency_email ON invitations(agency_id, email)
    WHERE kind = 'AGENCY' AND accepted_at IS NULL AND revoked_at IS NULL;
-- At most one pending owner invitation from the agency per clinic (ADR-028, accepted risk).
CREATE UNIQUE INDEX invitations_pending_agency_owner ON invitations(tenant_id)
    WHERE issuer_agency_id IS NOT NULL AND accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX invitations_tenant_idx ON invitations(tenant_id, created_at) WHERE kind = 'CLINIC';

-- Target, address and origin never change; an accepted or revoked invitation is final.
CREATE FUNCTION guard_invitation() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.invitation_id IS DISTINCT FROM OLD.invitation_id
        OR NEW.email IS DISTINCT FROM OLD.email
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.agency_id IS DISTINCT FROM OLD.agency_id
        OR NEW.issuer_agency_id IS DISTINCT FROM OLD.issuer_agency_id
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The target of an invitation is immutable' USING ERRCODE = 'BRL02';
    END IF;
    IF OLD.accepted_at IS NOT NULL OR OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'An accepted or revoked invitation is final' USING ERRCODE = 'BRL02';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER invitations_guard BEFORE UPDATE ON invitations
FOR EACH ROW EXECUTE FUNCTION guard_invitation();

-- The acceptance creates the account (verified: the token proves the address), resets an
-- unverified one, and adds or reactivates the membership. Column grants only: never
-- user_accounts.active, email or subject_id, never memberships of another subject or clinic
-- through UPDATE (V6 already limits it to role and active). No DELETE anywhere.
-- Agency memberships come with the platform command and the agency module.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT SELECT ON invitations TO crm_control_runtime;
        GRANT INSERT (token_hash, email, kind, tenant_id, agency_id, role, invited_by,
                      issuer_agency_id, expires_at, send_count, last_sent_at)
            ON invitations TO crm_control_runtime;
        GRANT UPDATE (token_hash, role, invited_by, expires_at, accepted_at, accepted_by,
                      revoked_at, revoked_by, send_count, last_sent_at)
            ON invitations TO crm_control_runtime;
        GRANT INSERT (email, display_name, password_hash, email_verified)
            ON user_accounts TO crm_control_runtime;
        GRANT UPDATE (display_name, password_hash, email_verified)
            ON user_accounts TO crm_control_runtime;
        GRANT INSERT (tenant_id, subject_id, role) ON tenant_memberships TO crm_control_runtime;
    END IF;
END;
$$;
