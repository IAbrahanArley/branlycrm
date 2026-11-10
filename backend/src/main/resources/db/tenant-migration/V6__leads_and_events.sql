-- Leads (opportunities) and their immutable events (CRM-031, ADR-033). Applied by the provisioner,
-- or by the tenant-migrate rollout, as the clinic owner role.

CREATE TABLE leads (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    pipeline_id UUID NOT NULL,
    stage_id UUID NOT NULL,
    status VARCHAR(8) NOT NULL CHECK (status IN ('OPEN', 'WON', 'LOST')),
    -- Null until someone classifies the lead (CRM-08).
    temperature VARCHAR(10) CHECK (temperature IN ('COLD', 'WARM', 'HOT', 'QUALIFIED')),
    -- Global subject_id of an active member, checked by the application (no FK across databases).
    responsible_id UUID,
    loss_reason_id UUID REFERENCES loss_reasons (id) ON DELETE RESTRICT,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    -- Null only for the WhatsApp worker (CRM-082).
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    -- The stage belongs to the lead's funnel.
    CONSTRAINT leads_stage_fk FOREIGN KEY (pipeline_id, stage_id)
        REFERENCES pipeline_stages (pipeline_id, id) ON DELETE RESTRICT,
    CONSTRAINT leads_closed_check CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    -- A loss always has its reason (CRM-06), and only a loss has one.
    CONSTRAINT leads_loss_reason_check CHECK ((status = 'LOST') = (loss_reason_id IS NOT NULL))
);

-- One open lead per contact (RN-01): a new one only after the previous is closed.
CREATE UNIQUE INDEX leads_one_open_per_contact ON leads (contact_id) WHERE status = 'OPEN';
CREATE INDEX leads_by_stage ON leads (stage_id) WHERE status = 'OPEN';
CREATE INDEX leads_by_responsible ON leads (responsible_id) WHERE status = 'OPEN';
CREATE INDEX leads_by_contact ON leads (contact_id, opened_at DESC);

-- The stage must be active and of the kind the status says (open, won or lost). Runs with the
-- writer's privileges, which include SELECT on the stages.
CREATE FUNCTION leads_stage_matches_status() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    stage_kind VARCHAR(8);
    stage_archived TIMESTAMPTZ;
BEGIN
    SELECT kind, archived_at INTO stage_kind, stage_archived
    FROM pipeline_stages WHERE id = NEW.stage_id AND pipeline_id = NEW.pipeline_id;
    IF stage_archived IS NOT NULL THEN
        RAISE EXCEPTION 'lead stage is archived' USING ERRCODE = 'BRL03';
    END IF;
    IF stage_kind IS DISTINCT FROM NEW.status THEN
        RAISE EXCEPTION 'lead stage does not match its status' USING ERRCODE = 'BRL03';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER leads_stage_matches_status
    BEFORE INSERT OR UPDATE OF stage_id, pipeline_id, status ON leads
    FOR EACH ROW EXECUTE FUNCTION leads_stage_matches_status();

-- Append-only timeline of each lead (CRM-09, RN-08).
CREATE TABLE lead_events (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE RESTRICT,
    type VARCHAR(24) NOT NULL CHECK (type IN (
        'CREATED', 'STAGE_CHANGED', 'TEMPERATURE_CHANGED', 'RESPONSIBLE_CHANGED', 'WON', 'LOST')),
    -- Previous and new value: a stage id, a temperature or a subject id, as text.
    from_value VARCHAR(40),
    to_value VARCHAR(40),
    loss_reason_id UUID REFERENCES loss_reasons (id) ON DELETE RESTRICT,
    -- Null only for the WhatsApp worker (CRM-082).
    actor_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- Write order: events of one change share the instant, so the timeline sorts by this.
    seq BIGINT GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX lead_events_timeline ON lead_events (lead_id, seq);

GRANT SELECT, INSERT ON leads TO ${runtimeRole};
GRANT UPDATE (stage_id, status, temperature, responsible_id, loss_reason_id, closed_at,
              updated_at, updated_by, version) ON leads TO ${runtimeRole};
GRANT SELECT, INSERT ON lead_events TO ${runtimeRole};
