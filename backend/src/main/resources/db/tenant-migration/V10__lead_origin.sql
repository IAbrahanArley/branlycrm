-- Origin of the lead (CRM-090, ORI-01, RN-02, RN-03). Applied by the provisioner, or by the
-- tenant-migrate rollout, as the clinic owner role. The first origin of a lead is never overwritten
-- automatically: later arrivals of the contact are origin touches, and only a manual correction
-- changes the lead's origin, with a timeline event. Sales inherit it through lead_id.

ALTER TABLE leads
    ADD COLUMN origin_type VARCHAR(20) NOT NULL DEFAULT 'UNIDENTIFIED' CHECK (origin_type IN (
        'PAID_TRAFFIC', 'INSTAGRAM_ORGANIC', 'REFERRAL', 'GOOGLE', 'OTHER', 'UNIDENTIFIED')),
    -- How the origin was known: Meta ad referral, [CODE] in the first message, or by hand.
    ADD COLUMN origin_source VARCHAR(8) NOT NULL DEFAULT 'NONE'
        CHECK (origin_source IN ('AD', 'CODE', 'MANUAL', 'NONE')),
    ADD COLUMN origin_ad_id VARCHAR(64) CHECK (btrim(origin_ad_id) <> ''),
    ADD COLUMN origin_code VARCHAR(32) CHECK (origin_code ~ '^[A-Za-z0-9_-]+$'),
    ADD COLUMN origin_title VARCHAR(200) CHECK (btrim(origin_title) <> ''),
    -- Meta's click id: kept for attribution, never returned by the API.
    ADD COLUMN origin_ctwa_clid VARCHAR(512) CHECK (btrim(origin_ctwa_clid) <> '');

CREATE INDEX leads_by_origin ON leads (origin_type, opened_at DESC);

-- The timeline records manual corrections of the origin.
ALTER TABLE lead_events DROP CONSTRAINT lead_events_type_check;
ALTER TABLE lead_events ADD CONSTRAINT lead_events_type_check CHECK (type IN (
    'CREATED', 'STAGE_CHANGED', 'TEMPERATURE_CHANGED', 'RESPONSIBLE_CHANGED', 'WON', 'LOST',
    'ORIGIN_CHANGED'));

-- Every later arrival of a contact with a detectable origin ("came organic, came back by ad").
-- Written by the WhatsApp worker (CRM-091); never changes the lead.
CREATE TABLE origin_touches (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    -- The contact's open lead at that moment, if any.
    lead_id UUID REFERENCES leads (id) ON DELETE RESTRICT,
    origin_type VARCHAR(20) NOT NULL CHECK (origin_type IN (
        'PAID_TRAFFIC', 'INSTAGRAM_ORGANIC', 'REFERRAL', 'GOOGLE', 'OTHER', 'UNIDENTIFIED')),
    origin_source VARCHAR(8) NOT NULL CHECK (origin_source IN ('AD', 'CODE', 'MANUAL', 'NONE')),
    ad_id VARCHAR(64) CHECK (btrim(ad_id) <> ''),
    code VARCHAR(32) CHECK (code ~ '^[A-Za-z0-9_-]+$'),
    title VARCHAR(200) CHECK (btrim(title) <> ''),
    ctwa_clid VARCHAR(512) CHECK (btrim(ctwa_clid) <> ''),
    received_at TIMESTAMPTZ NOT NULL,
    seq BIGINT GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX origin_touches_by_contact ON origin_touches (contact_id, seq DESC);

GRANT UPDATE (origin_type, origin_source, origin_ad_id, origin_code, origin_title,
              origin_ctwa_clid) ON leads TO ${runtimeRole};
GRANT SELECT, INSERT ON origin_touches TO ${runtimeRole};
