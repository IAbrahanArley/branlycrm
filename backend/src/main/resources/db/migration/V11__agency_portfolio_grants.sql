-- ADR-026 and ADR-028, last part: the agency portfolio and the platform command.

-- The API creates clinics for an agency portfolio: only these columns, so status always starts
-- PROVISIONING by default and the lifecycle trigger (V8) governs every later change. The
-- provisioning task needs only its key; everything else is default and admin_subject_id stays
-- NULL (the owner arrives by invitation).
-- Accepting an agency admin invitation adds or reactivates the agency membership; UPDATE on
-- active also allows locking that row.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT INSERT (tenant_id, clinic_name, database_name, database_user, credential_reference,
                      agency_id, agency_request_id)
            ON tenants TO crm_control_runtime;
        GRANT INSERT (tenant_id) ON tenant_provisioning_tasks TO crm_control_runtime;
        GRANT INSERT (agency_id, subject_id, role) ON agency_memberships TO crm_control_runtime;
        GRANT UPDATE (active) ON agency_memberships TO crm_control_runtime;
    END IF;
END;
$$;

-- crm_platform_operator runs the platform command (APP_MODE=platform), never the API: it creates
-- agencies and agency admin invitations, and nothing else. It cannot read accounts, clinics or
-- memberships.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_platform_operator') THEN
        GRANT SELECT ON agencies TO crm_platform_operator;
        GRANT INSERT (name, created_request_id) ON agencies TO crm_platform_operator;
        GRANT SELECT (invitation_id, kind, agency_id, email, role, accepted_at, revoked_at,
                      send_count)
            ON invitations TO crm_platform_operator;
        GRANT INSERT (token_hash, email, kind, agency_id, role, expires_at, send_count,
                      last_sent_at)
            ON invitations TO crm_platform_operator;
        GRANT UPDATE (token_hash, expires_at, send_count, last_sent_at)
            ON invitations TO crm_platform_operator;
    END IF;
END;
$$;
