-- Media of received WhatsApp messages (CRM-084b1, ADR-036). The inbound worker registers each one
-- PENDING with the message; the media step downloads it from Meta, checks its type by the first
-- bytes and stores it in the private bucket. The object key comes from this row only, never from
-- the client. Applied by the provisioner, or by the tenant-migrate rollout, as the clinic owner.
--
-- Files may show health data (photos, exams): they follow the contact's retention (CRM-073).
-- Rollback: drop the table (the objects stay in the bucket until removed by key).

CREATE TABLE whatsapp_media (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE REFERENCES whatsapp_messages (id) ON DELETE RESTRICT,
    -- Meta's media id, valid for a limited time.
    meta_media_id VARCHAR(128) NOT NULL CHECK (btrim(meta_media_id) <> ''),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'STORED', 'REJECTED', 'FAILED')),
    -- Detected from the first bytes, never the declared type.
    content_type VARCHAR(100),
    size_bytes BIGINT CHECK (size_bytes >= 0),
    sha256 CHAR(64) CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key VARCHAR(300) UNIQUE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(40) CHECK (error_code ~ '^[A-Z0-9_]{1,40}$'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT whatsapp_media_stored_check CHECK (
        (status = 'STORED')
        = (storage_key IS NOT NULL AND content_type IS NOT NULL AND size_bytes IS NOT NULL
           AND sha256 IS NOT NULL)),
    CONSTRAINT whatsapp_media_error_check
        CHECK ((status IN ('REJECTED', 'FAILED')) = (error_code IS NOT NULL))
);

CREATE INDEX whatsapp_media_pending ON whatsapp_media (next_attempt_at) WHERE status = 'PENDING';

GRANT SELECT, INSERT ON whatsapp_media TO ${runtimeRole};
GRANT UPDATE (status, content_type, size_bytes, sha256, storage_key, attempts, next_attempt_at,
              error_code, updated_at)
    ON whatsapp_media TO ${runtimeRole};
