-- ADR-020: five clinic roles. Order matters: the V2 CHECK only accepts ADMIN/RECEPTION/PROFESSIONAL,
-- so it is dropped before converting ADMIN to OWNER. No IF EXISTS: an unexpected constraint name
-- must fail the migration instead of leaving two CHECKs active.
ALTER TABLE tenant_memberships DROP CONSTRAINT tenant_memberships_role_check;
UPDATE tenant_memberships SET role = 'OWNER' WHERE role = 'ADMIN';
ALTER TABLE tenant_memberships ADD CONSTRAINT tenant_memberships_role_check
    CHECK (role IN ('OWNER', 'MANAGER', 'RECEPTION', 'SALES', 'PROFESSIONAL'));

-- An owner counts only with an active membership and an active, verified account: the same
-- condition under which TenantRegistryRepository authorizes access.
-- Every function pins search_path with pg_temp last: otherwise a temporary table named like a
-- control table would shadow it and fool the owner checks.
CREATE FUNCTION clinic_has_active_owner(clinic UUID, excluded_subject UUID) RETURNS boolean
LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM tenant_memberships m
        JOIN user_accounts u ON u.subject_id = m.subject_id
        WHERE m.tenant_id = clinic AND m.role = 'OWNER' AND m.active
          AND u.active AND u.email_verified
          AND m.subject_id IS DISTINCT FROM excluded_subject);
$$;

-- Every ACTIVE clinic keeps at least one owner. The row lock on the clinic serializes concurrent
-- team changes, and plpgsql takes a new snapshot per statement under READ COMMITTED, so two owners
-- demoting each other cannot both succeed. FOR NO KEY UPDATE does not conflict with the KEY SHARE
-- lock taken by membership foreign keys. SQLSTATE BRL01 is mapped to a stable domain error.
CREATE FUNCTION protect_last_clinic_owner() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    clinic_status VARCHAR(20);
BEGIN
    IF OLD.role <> 'OWNER' OR NOT OLD.active THEN
        RETURN NULL;
    END IF;
    -- Still the same owner of the same clinic: nothing to protect. Moving the row to another
    -- clinic or subject counts as losing the owner.
    IF TG_OP = 'UPDATE' AND NEW.role = 'OWNER' AND NEW.active
        AND NEW.tenant_id = OLD.tenant_id AND NEW.subject_id = OLD.subject_id THEN
        RETURN NULL;
    END IF;
    SELECT status INTO clinic_status FROM tenants WHERE tenant_id = OLD.tenant_id FOR NO KEY UPDATE;
    IF clinic_status = 'ACTIVE' AND NOT clinic_has_active_owner(OLD.tenant_id, OLD.subject_id) THEN
        RAISE EXCEPTION 'An active clinic must keep at least one owner' USING ERRCODE = 'BRL01';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER clinic_owner_guard AFTER UPDATE OR DELETE ON tenant_memberships
FOR EACH ROW EXECUTE FUNCTION protect_last_clinic_owner();

-- Activation (from PROVISIONING, SUSPENDED or FAILED) also requires an owner. The provisioner adds
-- the OWNER membership before changing the status in the same transaction.
CREATE FUNCTION require_owner_on_activation() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.status = 'ACTIVE' AND OLD.status IS DISTINCT FROM 'ACTIVE'
        AND NOT clinic_has_active_owner(NEW.tenant_id, NULL) THEN
        RAISE EXCEPTION 'A clinic cannot become active without an owner' USING ERRCODE = 'BRL01';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER clinic_activation_owner_guard AFTER UPDATE OF status ON tenants
FOR EACH ROW EXECUTE FUNCTION require_owner_on_activation();

-- The API only changes a member's role or active flag and never deletes memberships (ADR-003):
-- narrow the runtime grant from V4 accordingly. Tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        REVOKE UPDATE, DELETE ON tenant_memberships FROM crm_control_runtime;
        GRANT UPDATE (role, active) ON tenant_memberships TO crm_control_runtime;
    END IF;
END;
$$;

-- Existing data must already satisfy the invariant.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tenants t WHERE t.status = 'ACTIVE'
               AND NOT clinic_has_active_owner(t.tenant_id, NULL)) THEN
        RAISE EXCEPTION 'V6: an ACTIVE clinic has no active owner; fix the data before migrating';
    END IF;
END;
$$;
