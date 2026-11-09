-- Contacts, tags and consent history (CRM-020, ADR-032). Applied by the provisioner, or by the
-- tenant-migrate rollout, as the clinic owner role; the runtime gets explicit, minimal grants.

-- Trusted since PostgreSQL 13: the database owner creates it without a superuser.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE contacts (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL CHECK (btrim(name) <> ''),
    -- Accent- and case-free name computed by the application (SearchText), for searching.
    name_search VARCHAR(150) NOT NULL,
    -- Canonical E.164; Brazilian numbers in the national plan with the ninth mobile digit.
    phone_e164 VARCHAR(16) NOT NULL
        CHECK (phone_e164 ~ '^\+[1-9][0-9]{7,14}$')
        CHECK (phone_e164 !~ '^\+55' OR phone_e164 ~ '^\+55[1-9]{2}(9[0-9]{8}|[2-5][0-9]{7})$'),
    email VARCHAR(254) CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
    -- "Not in the future" is checked by the application: current_date is not immutable.
    birth_date DATE CHECK (birth_date IS NULL OR birth_date >= DATE '1900-01-01'),
    -- Free text that may receive health data: capped, never indexed nor searched.
    notes VARCHAR(2000),
    created_via VARCHAR(16) NOT NULL CHECK (created_via IN ('MANUAL', 'IMPORT', 'WHATSAPP')),
    created_at TIMESTAMPTZ NOT NULL,
    -- Global subject_id; null only for the WhatsApp worker (CRM-082).
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    archived_at TIMESTAMPTZ,
    archived_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    -- Archived contacts keep their number: one person, one contact per clinic.
    CONSTRAINT contacts_phone_e164_key UNIQUE (phone_e164),
    CONSTRAINT contacts_archived_by_check CHECK ((archived_at IS NULL) = (archived_by IS NULL)),
    -- Only a contact created from WhatsApp (by the worker) may have no author.
    CONSTRAINT contacts_created_by_check CHECK (created_by IS NOT NULL OR created_via = 'WHATSAPP')
);

CREATE INDEX contacts_name_trgm ON contacts USING gin (name_search gin_trgm_ops);
CREATE INDEX contacts_phone_trgm ON contacts USING gin (phone_e164 gin_trgm_ops);
CREATE INDEX contacts_email_lower ON contacts (lower(email) text_pattern_ops)
    WHERE email IS NOT NULL;
CREATE INDEX contacts_active_name ON contacts (name_search, id) WHERE archived_at IS NULL;

CREATE TABLE tags (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL
);
CREATE UNIQUE INDEX tags_name_key ON tags (lower(name));

CREATE TABLE contact_tags (
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    -- Deleting a tag removes its links; this runs with the table owner's privileges.
    tag_id UUID NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    PRIMARY KEY (contact_id, tag_id)
);
CREATE INDEX contact_tags_by_tag ON contact_tags (tag_id, contact_id);

-- Append-only history (PAC-08): the current state is the most recent record per channel.
CREATE TABLE contact_consents (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('WHATSAPP', 'EMAIL')),
    status VARCHAR(8) NOT NULL CHECK (status IN ('OPT_IN', 'OPT_OUT')),
    source VARCHAR(16) NOT NULL CHECK (source IN ('MANUAL', 'IMPORT', 'WHATSAPP')),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by UUID,
    -- Only a request made in a WhatsApp conversation (by the worker) may have no author.
    CONSTRAINT contact_consents_recorded_by_check CHECK (recorded_by IS NOT NULL OR source = 'WHATSAPP')
);
CREATE INDEX contact_consents_current ON contact_consents (contact_id, channel, recorded_at DESC);

-- Contacts are archived, never deleted; identity and creation columns never change.
GRANT SELECT, INSERT ON contacts TO ${runtimeRole};
GRANT UPDATE (name, name_search, phone_e164, email, birth_date, notes, updated_at, updated_by,
              archived_at, archived_by, version) ON contacts TO ${runtimeRole};
GRANT SELECT, INSERT, DELETE ON tags TO ${runtimeRole};
GRANT UPDATE (name) ON tags TO ${runtimeRole};
GRANT SELECT, INSERT, DELETE ON contact_tags TO ${runtimeRole};
GRANT SELECT, INSERT ON contact_consents TO ${runtimeRole};
