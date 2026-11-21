-- WhatsApp conversations and messages (CRM-082b, ADR-036). The worker writes them from the inbound
-- events; the inbox API (CRM-083) reads them. Applied by the provisioner, or by the tenant-migrate
-- rollout, as the clinic owner role.
--
-- Message bodies may carry health data (patients describe symptoms): they follow the contact's
-- retention and deletion (CRM-073), never logged nor indexed for search.

-- One conversation per contact in the MVP (one live channel per clinic, ADR-036).
CREATE TABLE whatsapp_conversations (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL UNIQUE REFERENCES contacts (id) ON DELETE RESTRICT,
    -- Central channel of the latest message (no FK: the registry lives in the control database).
    channel_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL,
    -- The 24-hour customer service window (CRM-085) counts from the last inbound message.
    last_inbound_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX whatsapp_conversations_recent ON whatsapp_conversations (last_message_at DESC, id);

CREATE TABLE whatsapp_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES whatsapp_conversations (id) ON DELETE RESTRICT,
    wamid VARCHAR(256) NOT NULL UNIQUE CHECK (btrim(wamid) <> ''),
    -- IN: from the contact. OUT: sent by the clinic (here only echoes of the WhatsApp Business app;
    -- the CRM's own sends come with CRM-084).
    direction VARCHAR(3) NOT NULL CHECK (direction IN ('IN', 'OUT')),
    sent_by_app BOOLEAN NOT NULL DEFAULT false,
    message_type VARCHAR(16) NOT NULL CHECK (message_type ~ '^[a-z_]{1,16}$'),
    -- Text, caption or button title; NUL removed; capped at WhatsApp's own limit.
    body VARCHAR(4096),
    -- Meta's media id and type; the file itself is downloaded by CRM-084.
    media_id VARCHAR(128),
    media_mime VARCHAR(100),
    -- Meta's timestamp, and when the worker stored it.
    sent_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT whatsapp_messages_echo_check CHECK (NOT sent_by_app OR direction = 'OUT')
);

CREATE INDEX whatsapp_messages_by_conversation
    ON whatsapp_messages (conversation_id, sent_at DESC, id);

-- The worker and the API share the runtime role (ADR-036): reads and inserts; conversations update
-- only their timestamps; messages are never updated nor deleted here.
GRANT SELECT, INSERT ON whatsapp_conversations TO ${runtimeRole};
GRANT UPDATE (channel_id, last_message_at, last_inbound_at, version)
    ON whatsapp_conversations TO ${runtimeRole};
GRANT SELECT, INSERT ON whatsapp_messages TO ${runtimeRole};
