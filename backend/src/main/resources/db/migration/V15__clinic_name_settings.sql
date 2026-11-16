-- CRM-013: the clinic owner renames the clinic through the API. The runtime gains UPDATE on
-- clinic_name only; identity, database binding, agency and status stay guarded by the V2 and V8
-- triggers and by the missing grants. Rollback: REVOKE UPDATE (clinic_name) ON tenants FROM
-- crm_control_runtime.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT UPDATE (clinic_name) ON tenants TO crm_control_runtime;
    END IF;
END;
$$;
