-- One persistent task per clinic: retries reuse the same tenant UUID and derived names (ADR-014).
CREATE TABLE tenant_provisioning_tasks (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(tenant_id) ON DELETE RESTRICT,
    admin_subject_id UUID NOT NULL REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    step VARCHAR(20) NOT NULL DEFAULT 'SECRET'
        CHECK (step IN ('SECRET', 'ROLES', 'DATABASE', 'MIGRATION', 'VERIFICATION', 'ACTIVATION', 'DONE')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    -- Stable diagnostic code only; never SQL text, secrets or stack traces.
    last_error VARCHAR(60) CHECK (last_error ~ '^[A-Z_]+$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'RUNNING') = (locked_until IS NOT NULL)),
    CHECK ((status = 'DONE') = (step = 'DONE'))
);
CREATE INDEX tenant_provisioning_ready_idx ON tenant_provisioning_tasks(next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- Infrastructure bootstrap creates the worker role; tests can omit deployment roles.
-- The worker reads account state but never password hashes.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_provisioning_worker') THEN
        GRANT SELECT, UPDATE ON tenant_provisioning_tasks TO crm_provisioning_worker;
        GRANT SELECT (tenant_id, database_name, database_user, credential_reference, status)
            ON tenants TO crm_provisioning_worker;
        GRANT UPDATE (status) ON tenants TO crm_provisioning_worker;
        GRANT SELECT, INSERT ON tenant_memberships TO crm_provisioning_worker;
        GRANT SELECT (subject_id, active, email_verified) ON user_accounts TO crm_provisioning_worker;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT SELECT ON tenant_provisioning_tasks TO crm_control_runtime;
    END IF;
END;
$$;
