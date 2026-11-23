-- WhatsApp outbox (CRM-084a, ADR-036, ADR-005). The API queues a reply; the worker sends it and
-- records the result; delivery statuses from the webhook reconcile it. Applied by the provisioner,
-- or by the tenant-migrate rollout, as the clinic owner role.
--
-- A send that may or may not have reached Meta (timeout, connection lost after the request) is
-- UNCERTAIN and never sent again automatically; a status carrying its id
-- (biz_opaque_callback_data) reconciles it. The text may carry health data: it follows the
-- conversation's retention (CRM-073) and never reaches logs.
-- Rollback: drop the outbox, and the status and outbox_id columns of messages.

CREATE TABLE whatsapp_outbox (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES whatsapp_conversations (id) ON DELETE RESTRICT,
    -- Chosen by the client: the same request sent twice queues one message.
    idempotency_key UUID NOT NULL UNIQUE,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('TEXT')),
    body VARCHAR(4096) NOT NULL CHECK (btrim(body) <> ''),
    status VARCHAR(10) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED',
                          'UNCERTAIN')),
    wamid VARCHAR(256) UNIQUE CHECK (btrim(wamid) <> ''),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    -- While SENDING: after this instant the worker that held it is gone, and the send becomes
    -- UNCERTAIN (never resent).
    lease_until TIMESTAMPTZ,
    -- Stable code only (for example META_131047), never a message.
    error_code VARCHAR(40) CHECK (error_code ~ '^[A-Z0-9_]{1,40}$'),
    -- Global subject_id of the member who wrote it.
    requested_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT whatsapp_outbox_failed_check CHECK ((status = 'FAILED') = (error_code IS NOT NULL)),
    CONSTRAINT whatsapp_outbox_sent_check
        CHECK (status NOT IN ('SENT', 'DELIVERED', 'READ') OR wamid IS NOT NULL)
);

CREATE INDEX whatsapp_outbox_due ON whatsapp_outbox (next_attempt_at)
    WHERE status IN ('QUEUED', 'SENDING');
CREATE INDEX whatsapp_outbox_by_conversation ON whatsapp_outbox (conversation_id, created_at DESC);

-- Delivery status of a message the clinic sent from the CRM, and the outbox row it came from.
ALTER TABLE whatsapp_messages
    ADD COLUMN status VARCHAR(10) CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    ADD COLUMN outbox_id UUID UNIQUE REFERENCES whatsapp_outbox (id) ON DELETE RESTRICT;

-- The API queues (always QUEUED by default); the worker, with the same role, moves the rows.
GRANT SELECT, INSERT ON whatsapp_outbox TO ${runtimeRole};
GRANT UPDATE (status, wamid, attempts, next_attempt_at, lease_until, error_code, sent_at,
              updated_at)
    ON whatsapp_outbox TO ${runtimeRole};
GRANT UPDATE (status) ON whatsapp_messages TO ${runtimeRole};
