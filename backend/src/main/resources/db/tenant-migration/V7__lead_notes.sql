-- Notes written on a lead by the team (CRM-032). Applied by the provisioner, or by the
-- tenant-migrate rollout, as the clinic owner role. Notes are never edited nor deleted.
CREATE TABLE lead_notes (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE RESTRICT,
    -- Free text that may receive health data: capped, never indexed nor searched.
    body VARCHAR(2000) NOT NULL CHECK (btrim(body) <> ''),
    author_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- Write order, for a stable timeline when two notes share the instant.
    seq BIGINT GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX lead_notes_by_lead ON lead_notes (lead_id, seq);

GRANT SELECT, INSERT ON lead_notes TO ${runtimeRole};
