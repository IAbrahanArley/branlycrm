-- Shared inbox (CRM-083): who is serving a conversation and whether it needs a person. Additive
-- on V15. Applied by the provisioner, or by the tenant-migrate rollout, as the clinic owner role.
--
-- A conversation is WAITING (nobody holds it) or IN_SERVICE (one member holds it). Taking one is a
-- single conditional UPDATE, so two members never hold it together. New messages never change
-- the status. Every change is an event, for the audit trail.
-- Messages get their arrival order: Meta's timestamp has one-second precision and a message may
-- be stored late (retries), so the inbox orders and polls by arrival, never by Meta's time.
-- Rollback: drop the events table, the indexes, the four columns and messages.seq.

ALTER TABLE whatsapp_conversations
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'IN_SERVICE')),
    -- Global subject_id of an active member, checked by the application.
    ADD COLUMN assignee_id UUID,
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN needs_human BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT whatsapp_conversations_assignee_check
        CHECK ((status = 'IN_SERVICE') = (assignee_id IS NOT NULL)
               AND (assignee_id IS NULL) = (assigned_at IS NULL));

CREATE INDEX whatsapp_conversations_by_assignee
    ON whatsapp_conversations (assignee_id, last_message_at DESC) WHERE assignee_id IS NOT NULL;

CREATE TABLE whatsapp_conversation_events (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES whatsapp_conversations (id) ON DELETE RESTRICT,
    type VARCHAR(16) NOT NULL
        CHECK (type IN ('TAKEN', 'RELEASED', 'ASSIGNED', 'NEEDS_HUMAN', 'HUMAN_DONE')),
    -- The member now holding the conversation (TAKEN, ASSIGNED), or null.
    assignee_id UUID,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    seq BIGINT GENERATED ALWAYS AS IDENTITY
);

CREATE INDEX whatsapp_conversation_events_by_conversation
    ON whatsapp_conversation_events (conversation_id, seq);

GRANT UPDATE (status, assignee_id, assigned_at, needs_human)
    ON whatsapp_conversations TO ${runtimeRole};
GRANT SELECT, INSERT ON whatsapp_conversation_events TO ${runtimeRole};

-- Arrival order of messages (fills existing rows; the worker serializes per contact, and a contact
-- has one conversation, so within a conversation it follows the commit order).
ALTER TABLE whatsapp_messages ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE INDEX whatsapp_messages_by_arrival ON whatsapp_messages (conversation_id, seq);
