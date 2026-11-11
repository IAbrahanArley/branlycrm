-- Tasks and follow-ups (CRM-040, CRM-07). Applied by the provisioner, or by the tenant-migrate
-- rollout, as the clinic owner role. A task belongs to a contact and optionally to one of its
-- leads; it is completed or reopened, never deleted.

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    -- Free text that may receive health data: capped, never searched.
    description VARCHAR(2000),
    due_at TIMESTAMPTZ NOT NULL,
    -- Global subject_id of an active member, checked by the application.
    responsible_id UUID NOT NULL,
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    lead_id UUID REFERENCES leads (id) ON DELETE RESTRICT,
    status VARCHAR(8) NOT NULL CHECK (status IN ('OPEN', 'DONE')),
    completed_at TIMESTAMPTZ,
    completed_by UUID,
    completion_note VARCHAR(500) CHECK (btrim(completion_note) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT tasks_done_check CHECK (
        (status = 'DONE') = (completed_at IS NOT NULL AND completed_by IS NOT NULL)),
    CONSTRAINT tasks_open_check CHECK (
        status = 'DONE' OR (completed_at IS NULL AND completed_by IS NULL
                            AND completion_note IS NULL))
);

CREATE INDEX tasks_open_by_responsible ON tasks (responsible_id, due_at) WHERE status = 'OPEN';
CREATE INDEX tasks_open_by_due ON tasks (due_at) WHERE status = 'OPEN';
CREATE INDEX tasks_by_contact ON tasks (contact_id, due_at);
CREATE INDEX tasks_by_lead ON tasks (lead_id) WHERE lead_id IS NOT NULL;

GRANT SELECT, INSERT ON tasks TO ${runtimeRole};
GRANT UPDATE (title, description, due_at, responsible_id, status, completed_at, completed_by,
              completion_note, updated_at, updated_by, version) ON tasks TO ${runtimeRole};
