CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE tenant_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    tenant_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Provisioning must insert the expected tenant_id before activating the registry entry.
-- Runtime roles receive SELECT only on this table, never UPDATE or DELETE.