-- Infrastructure bootstrap creates this role in Docker/hosting. Tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='crm_control_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON tenants, tenant_memberships, user_accounts TO crm_control_runtime;
        GRANT SELECT ON flyway_schema_history TO crm_control_runtime;
    END IF;
END;
$$;