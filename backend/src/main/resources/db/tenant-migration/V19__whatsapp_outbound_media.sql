-- Media sent from the CRM (CRM-084b2, ADR-036, ADR-005). The API checks the file by its first
-- bytes, stores it in the private bucket and queues an outbox item of kind MEDIA pointing to a
-- whatsapp_media row (direction OUT, already STORED). The worker uploads the file to Meta, keeps
-- Meta's media id (a retry does not upload again) and sends the message; once sent, the media row
-- points to the message, so the history and the link work as for received files. Applied by the
-- provisioner, or by the tenant-migrate rollout, as the clinic owner role.
--
-- Files may show health data: they follow the contact's retention (CRM-073).
-- Rollback: drop the added columns and constraints and restore the old ones; delete MEDIA outbox
-- rows and OUT media rows first (their objects stay in the bucket until removed by key).

ALTER TABLE whatsapp_media
    ADD COLUMN direction VARCHAR(3) NOT NULL DEFAULT 'IN' CHECK (direction IN ('IN', 'OUT')),
    -- The name the patient sees for a sent document; sanitized by the API.
    ADD COLUMN file_name VARCHAR(120) CHECK (file_name ~ '^[^/\\[:cntrl:]]{1,120}$'),
    ALTER COLUMN message_id DROP NOT NULL,
    ALTER COLUMN meta_media_id DROP NOT NULL,
    -- Received media always have both; a sent one gets the message once sent.
    ADD CONSTRAINT whatsapp_media_inbound_check
        CHECK (direction = 'OUT' OR (message_id IS NOT NULL AND meta_media_id IS NOT NULL)),
    ADD CONSTRAINT whatsapp_media_outbound_check CHECK (direction = 'IN' OR status = 'STORED');

ALTER TABLE whatsapp_outbox
    DROP CONSTRAINT whatsapp_outbox_kind_check,
    DROP CONSTRAINT whatsapp_outbox_body_check,
    ALTER COLUMN body DROP NOT NULL,
    ADD COLUMN media_id UUID UNIQUE REFERENCES whatsapp_media (id) ON DELETE RESTRICT,
    ADD CONSTRAINT whatsapp_outbox_kind_check CHECK (kind IN ('TEXT', 'MEDIA')),
    -- Text: the message. Media: an optional caption (up to 1024 characters, Meta's limit).
    ADD CONSTRAINT whatsapp_outbox_body_check CHECK (
        (kind = 'TEXT' AND body IS NOT NULL AND btrim(body) <> '' AND media_id IS NULL)
        OR (kind = 'MEDIA' AND media_id IS NOT NULL
            AND (body IS NULL OR (btrim(body) <> '' AND char_length(body) <= 1024))));

GRANT UPDATE (message_id, meta_media_id) ON whatsapp_media TO ${runtimeRole};
