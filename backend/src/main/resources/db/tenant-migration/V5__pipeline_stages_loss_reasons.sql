-- Sales funnel, its stages and loss reasons (CRM-030, ADR-033). Applied by the provisioner, or by
-- the tenant-migrate rollout, as the clinic owner role. One funnel per clinic in the MVP: the
-- runtime cannot insert nor delete funnels. Writes to the configuration come with CRM-036.

CREATE TABLE pipelines (
    id UUID PRIMARY KEY,
    name VARCHAR(60) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    -- Bumped by every change to the stages, so reorders are checked against the order read.
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE pipeline_stages (
    id UUID PRIMARY KEY,
    pipeline_id UUID NOT NULL REFERENCES pipelines (id) ON DELETE RESTRICT,
    name VARCHAR(40) NOT NULL CHECK (btrim(name) <> ''),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('OPEN', 'WON', 'LOST')),
    -- Stable key of the default stages, whatever their name; null for stages a clinic creates.
    system_key VARCHAR(16)
        CHECK (system_key IN ('NEW', 'IN_SERVICE', 'SCHEDULED', 'ATTENDED', 'WON', 'LOST')),
    -- Only active open stages have a position; won and lost are always shown after them.
    position INTEGER CHECK (position > 0),
    created_at TIMESTAMPTZ NOT NULL,
    -- Null only for the default stages written by this migration.
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    archived_at TIMESTAMPTZ,
    archived_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pipeline_stages_archived_by_check
        CHECK ((archived_at IS NULL) = (archived_by IS NULL)),
    -- The won and lost stages, and the entry stage, are never archived.
    CONSTRAINT pipeline_stages_terminal_active CHECK (kind = 'OPEN' OR archived_at IS NULL),
    CONSTRAINT pipeline_stages_entry_active
        CHECK (system_key IS DISTINCT FROM 'NEW' OR archived_at IS NULL),
    CONSTRAINT pipeline_stages_key_kind CHECK (
        system_key IS NULL
        OR (system_key = 'WON' AND kind = 'WON')
        OR (system_key = 'LOST' AND kind = 'LOST')
        OR (system_key NOT IN ('WON', 'LOST') AND kind = 'OPEN')),
    -- (Not named *_position_check: that is the default name of the column check above.)
    CONSTRAINT pipeline_stages_position_only_open
        CHECK ((position IS NOT NULL) = (kind = 'OPEN' AND archived_at IS NULL)),
    CONSTRAINT pipeline_stages_created_by_check
        CHECK (created_by IS NOT NULL OR system_key IS NOT NULL),
    -- Checked at the end of each statement, so one UPDATE can swap positions.
    CONSTRAINT pipeline_stages_position_key UNIQUE (pipeline_id, position)
        DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT pipeline_stages_key_unique UNIQUE (pipeline_id, system_key),
    -- Target of the composite foreign key of leads (CRM-031): a lead's stage is in its funnel.
    CONSTRAINT pipeline_stages_pipeline_id_key UNIQUE (pipeline_id, id)
);

-- Exactly one won and one lost stage per funnel: at most one here, at least one because the seed
-- creates them, they are never archived and the runtime can neither delete nor change their kind.
CREATE UNIQUE INDEX pipeline_stages_one_won ON pipeline_stages (pipeline_id) WHERE kind = 'WON';
CREATE UNIQUE INDEX pipeline_stages_one_lost ON pipeline_stages (pipeline_id) WHERE kind = 'LOST';
CREATE UNIQUE INDEX pipeline_stages_active_name ON pipeline_stages (pipeline_id, lower(name))
    WHERE archived_at IS NULL;

CREATE TABLE loss_reasons (
    id UUID PRIMARY KEY,
    name VARCHAR(60) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    -- True only for the initial reasons written by this migration, the only ones without author.
    seeded BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    archived_at TIMESTAMPTZ,
    archived_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT loss_reasons_archived_by_check
        CHECK ((archived_at IS NULL) = (archived_by IS NULL)),
    CONSTRAINT loss_reasons_created_by_check CHECK (created_by IS NOT NULL OR seeded)
);
-- Archived reasons keep their name: unarchive one instead of creating it again.
CREATE UNIQUE INDEX loss_reasons_name_key ON loss_reasons (lower(name));

-- Default funnel and reasons. Ids are random, so they differ in every clinic.
WITH funnel AS (
    INSERT INTO pipelines (id, name, created_at, updated_at)
    VALUES (gen_random_uuid(), 'Funil de vendas', now(), now())
    RETURNING id
)
INSERT INTO pipeline_stages (id, pipeline_id, name, kind, system_key, position, created_at, updated_at)
SELECT gen_random_uuid(), funnel.id, stage.name, stage.kind, stage.system_key, stage.position,
       now(), now()
FROM funnel,
     (VALUES ('Novo', 'OPEN', 'NEW', 1),
             ('Em atendimento', 'OPEN', 'IN_SERVICE', 2),
             ('Agendado', 'OPEN', 'SCHEDULED', 3),
             ('Compareceu', 'OPEN', 'ATTENDED', 4),
             ('Venda fechada', 'WON', 'WON', NULL),
             ('Perdido', 'LOST', 'LOST', NULL)) AS stage (name, kind, system_key, position);

INSERT INTO loss_reasons (id, name, seeded, created_at, updated_at)
SELECT gen_random_uuid(), reason, true, now(), now()
FROM unnest(ARRAY['Preço', 'Sem resposta', 'Escolheu outra clínica', 'Sem horário disponível',
                  'Desistiu', 'Contato inválido', 'Outro']) AS reason;

-- Read only until the configuration writes of CRM-036.
GRANT SELECT ON pipelines, pipeline_stages, loss_reasons TO ${runtimeRole};
