-- Message templates (CRM-085, ADR-036). Templates are created and approved in Meta's WhatsApp
-- Manager; the worker copies the account's templates here and the inbox sends only APPROVED and
-- supported ones, inside or outside the 24-hour window. Templates sent per day (in the clinic's
-- time zone) are counted atomically and capped. Applied by the provisioner, or by the
-- tenant-migrate rollout, as the clinic owner role.
--
-- Template definitions carry no personal data; the values filled in a send may, and follow the
-- conversation's retention (CRM-073).
-- Rollback: delete TEMPLATE outbox rows, restore the V19 checks on whatsapp_outbox, drop the added
-- columns and the two tables.

CREATE TABLE whatsapp_templates (
    id UUID PRIMARY KEY,
    meta_template_id VARCHAR(32) NOT NULL UNIQUE CHECK (meta_template_id ~ '^[0-9]{1,32}$'),
    name VARCHAR(512) NOT NULL CHECK (name ~ '^[a-z0-9_]+$'),
    language VARCHAR(16) NOT NULL CHECK (language ~ '^[A-Za-z]{2,3}(_[A-Za-z0-9]{2,8})?$'),
    category VARCHAR(16) NOT NULL CHECK (category ~ '^[A-Z_]{1,16}$'),
    -- Meta's status (APPROVED, PENDING, REJECTED, PAUSED, DISABLED...), or DELETED when the sync
    -- no longer finds it.
    status VARCHAR(20) NOT NULL CHECK (status ~ '^[A-Z_]{1,20}$'),
    -- Text header (or none), body with parameters, footer and buttons without parameters (MVP).
    supported BOOLEAN NOT NULL,
    parameter_format VARCHAR(10) NOT NULL CHECK (parameter_format IN ('POSITIONAL', 'NAMED')),
    header_text VARCHAR(60),
    body_text VARCHAR(1024),
    footer_text VARCHAR(60),
    -- Parameter keys in order, as JSON arrays: "1", "2"... (positional) or names (named).
    header_params JSONB NOT NULL DEFAULT '[]',
    body_params JSONB NOT NULL DEFAULT '[]',
    synced_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT whatsapp_templates_supported_check CHECK (NOT supported OR body_text IS NOT NULL)
);

-- A name and language are unique among the account's live templates; one deleted and created again
-- in Meta comes back with a new id.
CREATE UNIQUE INDEX whatsapp_templates_live_name ON whatsapp_templates (name, language)
    WHERE status <> 'DELETED';

-- Templates queued per day, in the clinic's time zone; the API increments it in the same
-- transaction as the outbox row, only while under the limit.
CREATE TABLE whatsapp_template_usage (
    day DATE PRIMARY KEY,
    queued INTEGER NOT NULL CHECK (queued >= 0)
);

ALTER TABLE whatsapp_outbox
    DROP CONSTRAINT whatsapp_outbox_kind_check,
    DROP CONSTRAINT whatsapp_outbox_body_check,
    ADD COLUMN template_id UUID REFERENCES whatsapp_templates (id) ON DELETE RESTRICT,
    -- The values filled in, by section and key: {"header": {...}, "body": {...}}.
    ADD COLUMN template_values JSONB,
    ADD CONSTRAINT whatsapp_outbox_kind_check CHECK (kind IN ('TEXT', 'MEDIA', 'TEMPLATE')),
    -- Text: the message. Media: an optional caption. Template: the text as the patient sees it.
    ADD CONSTRAINT whatsapp_outbox_body_check CHECK (
        (kind = 'TEXT' AND body IS NOT NULL AND btrim(body) <> '' AND media_id IS NULL
         AND template_id IS NULL)
        OR (kind = 'MEDIA' AND media_id IS NOT NULL AND template_id IS NULL
            AND (body IS NULL OR (btrim(body) <> '' AND char_length(body) <= 1024)))
        OR (kind = 'TEMPLATE' AND template_id IS NOT NULL AND template_values IS NOT NULL
            AND media_id IS NULL AND body IS NOT NULL AND btrim(body) <> ''));

-- The worker (runtime role) writes the synced templates; the API reads them and counts usage.
GRANT SELECT, INSERT ON whatsapp_templates TO ${runtimeRole};
GRANT UPDATE (category, status, supported, parameter_format, header_text, body_text, footer_text,
              header_params, body_params, synced_at, updated_at)
    ON whatsapp_templates TO ${runtimeRole};
GRANT SELECT, INSERT ON whatsapp_template_usage TO ${runtimeRole};
GRANT UPDATE (queued) ON whatsapp_template_usage TO ${runtimeRole};
