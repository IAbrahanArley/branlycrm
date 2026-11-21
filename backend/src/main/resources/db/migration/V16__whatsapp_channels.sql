-- WhatsApp channel registry (CRM-081a, ADR-036). Routing only: no messages nor patient data here.
-- Tokens and PINs are sealed with the channel public key; only the WhatsApp worker can open them,
-- and the API role cannot even read the sealed columns.

CREATE TABLE whatsapp_channels (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id) ON DELETE RESTRICT,
    -- Known only after the worker confirms them from the token (never taken from the browser).
    waba_id VARCHAR(32) CHECK (waba_id ~ '^[0-9]{1,32}$'),
    phone_number_id VARCHAR(32) CHECK (phone_number_id ~ '^[0-9]{1,32}$'),
    display_phone_number VARCHAR(32) CHECK (btrim(display_phone_number) <> ''),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'FAILED', 'DISCONNECTED')),
    failure_code VARCHAR(40) CHECK (failure_code ~ '^[A-Z0-9_]{1,40}$'),
    sealed_token BYTEA CHECK (octet_length(sealed_token) BETWEEN 1 AND 4096),
    sealed_pin BYTEA CHECK (octet_length(sealed_pin) BETWEEN 1 AND 4096),
    key_version INTEGER CHECK (key_version > 0),
    -- Durable "events waiting" hint for the worker sweep, written by the webhook after its commit.
    inbound_pending_since TIMESTAMPTZ,
    connected_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    disconnected_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT whatsapp_channels_assets_check
        CHECK (status IN ('PENDING', 'FAILED', 'DISCONNECTED')
               OR (waba_id IS NOT NULL AND phone_number_id IS NOT NULL)),
    CONSTRAINT whatsapp_channels_active_token_check
        CHECK (status <> 'ACTIVE' OR (sealed_token IS NOT NULL AND key_version IS NOT NULL)),
    CONSTRAINT whatsapp_channels_sealed_version_check
        CHECK ((sealed_token IS NULL AND sealed_pin IS NULL) OR key_version IS NOT NULL),
    CONSTRAINT whatsapp_channels_disconnected_check
        CHECK ((status = 'DISCONNECTED') = (disconnected_at IS NOT NULL)),
    CONSTRAINT whatsapp_channels_disconnected_wiped_check
        CHECK (status <> 'DISCONNECTED' OR (sealed_token IS NULL AND sealed_pin IS NULL)),
    CONSTRAINT whatsapp_channels_failure_check
        CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

-- A number belongs to one live channel; in the MVP a clinic has at most one live channel.
CREATE UNIQUE INDEX whatsapp_channels_live_phone ON whatsapp_channels (phone_number_id)
    WHERE status <> 'DISCONNECTED';
CREATE UNIQUE INDEX whatsapp_channels_live_tenant ON whatsapp_channels (tenant_id)
    WHERE status <> 'DISCONNECTED';
CREATE INDEX whatsapp_channels_inbound_pending ON whatsapp_channels (inbound_pending_since)
    WHERE inbound_pending_since IS NOT NULL;

-- Connection steps run by the worker after the API exchanged the code and sealed the token.
CREATE TABLE whatsapp_onboarding_tasks (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL REFERENCES whatsapp_channels (id) ON DELETE RESTRICT,
    -- One task per connection attempt: a resent request reuses it.
    idempotency_key UUID NOT NULL UNIQUE,
    step VARCHAR(20) NOT NULL
        CHECK (step IN ('VERIFY_ASSETS', 'REGISTER_NUMBER', 'SUBSCRIBE_WEBHOOKS', 'DONE')),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(40) CHECK (last_error ~ '^[A-Z0-9_]{1,40}$'),
    requested_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX whatsapp_onboarding_ready ON whatsapp_onboarding_tasks (next_attempt_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- Infrastructure bootstrap creates the roles; tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        -- The API reads routing columns, never the sealed ones.
        GRANT SELECT (id, tenant_id, waba_id, phone_number_id, display_phone_number, status,
                      failure_code, key_version, inbound_pending_since, connected_by,
                      created_at, updated_at, disconnected_at, version)
            ON whatsapp_channels TO crm_control_runtime;
        -- It creates a PENDING channel with the token it sealed right after the code exchange.
        GRANT INSERT (id, tenant_id, status, sealed_token, key_version, connected_by, created_at,
                      updated_at)
            ON whatsapp_channels TO crm_control_runtime;
        -- The webhook raises the durable hint after committing the events.
        GRANT UPDATE (inbound_pending_since) ON whatsapp_channels TO crm_control_runtime;
        -- Tasks start PENDING by default: the API cannot create them running or done.
        GRANT SELECT ON whatsapp_onboarding_tasks TO crm_control_runtime;
        GRANT INSERT (id, channel_id, idempotency_key, step, next_attempt_at, requested_by,
                      created_at, updated_at)
            ON whatsapp_onboarding_tasks TO crm_control_runtime;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_whatsapp_worker') THEN
        -- Never the clinic, the id nor the authorship: openForChannel trusts tenant_id.
        GRANT SELECT ON whatsapp_channels TO crm_whatsapp_worker;
        GRANT UPDATE (waba_id, phone_number_id, display_phone_number, status, failure_code,
                      sealed_token, sealed_pin, key_version, inbound_pending_since,
                      updated_at, disconnected_at, version)
            ON whatsapp_channels TO crm_whatsapp_worker;
        GRANT SELECT ON whatsapp_onboarding_tasks TO crm_whatsapp_worker;
        GRANT UPDATE (step, status, attempts, next_attempt_at, lease_until, last_error,
                      updated_at)
            ON whatsapp_onboarding_tasks TO crm_whatsapp_worker;
        -- Opening a clinic database: the registry row, never memberships nor accounts.
        GRANT SELECT (tenant_id, database_name, database_user, credential_reference, status)
            ON tenants TO crm_whatsapp_worker;
    END IF;
END;
$$;
