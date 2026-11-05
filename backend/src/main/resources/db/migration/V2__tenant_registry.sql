CREATE TABLE tenants (
    tenant_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_name VARCHAR(160) NOT NULL CHECK (length(btrim(clinic_name)) BETWEEN 1 AND 160),
    database_name VARCHAR(63) NOT NULL UNIQUE CHECK (database_name ~ '^clinic_[a-f0-9]{32}$'),
    database_user VARCHAR(63) NOT NULL UNIQUE CHECK (database_user ~ '^tenant_[a-f0-9]{32}$'),
    credential_reference VARCHAR(160) NOT NULL UNIQUE CHECK (credential_reference ~ '^TENANT_SECRET_[A-F0-9]{32}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'PROVISIONING'
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (database_name = 'clinic_' || replace(tenant_id::text, '-', '')),
    CHECK (database_user = 'tenant_' || replace(tenant_id::text, '-', '')),
    CHECK (credential_reference = 'TENANT_SECRET_' || upper(replace(tenant_id::text, '-', '')))
);

-- Subject IDs will refer to the verified identity provided by the login module.
CREATE TABLE tenant_memberships (
    tenant_id UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE RESTRICT,
    subject_id UUID NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('ADMIN', 'RECEPTION', 'PROFESSIONAL')),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, subject_id)
);
CREATE INDEX memberships_subject_idx ON tenant_memberships(subject_id) WHERE active;

CREATE FUNCTION protect_tenant_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.database_name IS DISTINCT FROM OLD.database_name
        OR NEW.database_user IS DISTINCT FROM OLD.database_user
        OR NEW.credential_reference IS DISTINCT FROM OLD.credential_reference THEN
        RAISE EXCEPTION 'Tenant identity and database binding are immutable';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER tenants_identity_guard BEFORE UPDATE ON tenants
FOR EACH ROW EXECUTE FUNCTION protect_tenant_identity();