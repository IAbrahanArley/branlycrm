-- WhatsApp inbound events (CRM-082a, ADR-036). The webhook writes each message, delivery status
-- or account event here before answering 200; the worker (CRM-082b) processes them later. Applied
-- by the provisioner, or by the tenant-migrate rollout, as the clinic owner role.
--
-- The raw payload may carry health data (patients describe symptoms): it is cleared 30 days after
-- processing (ADR-036, LGPD), so it is nullable once processed.

CREATE TABLE whatsapp_inbound_events (
    id UUID PRIMARY KEY,
    -- Message: wamid. Status: wamid and status. Other events: SHA-256 of the change.
    dedup_key VARCHAR(200) NOT NULL UNIQUE,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('MESSAGE', 'STATUS', 'ACCOUNT')),
    field VARCHAR(64) NOT NULL CHECK (btrim(field) <> ''),
    -- Central channel that routed the event (no FK: the registry lives in the control database).
    channel_id UUID NOT NULL,
    wamid VARCHAR(256),
    -- Meta's own timestamp, the processing order inside a contact.
    event_at TIMESTAMPTZ,
    payload JSONB,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    -- Stable code only, never a message or SQL text.
    last_error VARCHAR(64),
    CONSTRAINT whatsapp_inbound_events_wamid_check CHECK (kind = 'ACCOUNT' OR wamid IS NOT NULL),
    CONSTRAINT whatsapp_inbound_events_payload_check CHECK (
        payload IS NOT NULL OR processed_at IS NOT NULL)
);

CREATE INDEX whatsapp_inbound_events_pending
    ON whatsapp_inbound_events (event_at, received_at) WHERE processed_at IS NULL;
CREATE INDEX whatsapp_inbound_events_retention
    ON whatsapp_inbound_events (processed_at) WHERE payload IS NOT NULL;

-- The webhook only inserts (ON CONFLICT DO NOTHING needs no UPDATE). The worker, with the same
-- runtime role, reads and marks the processing state and clears the payload; nobody deletes.
GRANT SELECT, INSERT ON whatsapp_inbound_events TO ${runtimeRole};
GRANT UPDATE (payload, processed_at, attempts, next_attempt_at, last_error)
    ON whatsapp_inbound_events TO ${runtimeRole};
