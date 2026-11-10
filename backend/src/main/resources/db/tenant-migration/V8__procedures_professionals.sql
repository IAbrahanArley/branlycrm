-- Catalog of procedures and professionals (CRM-034). Applied by the provisioner, or by the
-- tenant-migrate rollout, as the clinic owner role. Nothing is deleted: inactive items leave new
-- sales but stay on old ones (CRM-035). No clinical information is stored here.

CREATE TABLE procedures (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    -- Reference price in cents; the sale records the amount actually charged.
    reference_price_cents BIGINT NOT NULL
        CHECK (reference_price_cents >= 0 AND reference_price_cents <= 1000000000),
    category VARCHAR(60) CHECK (btrim(category) <> ''),
    -- Days until a return is usually due, for future reminders; optional.
    return_interval_days INTEGER CHECK (return_interval_days BETWEEN 1 AND 3650),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Unique whatever the case, inactive ones included, so reactivating never collides.
CREATE UNIQUE INDEX procedures_name_key ON procedures (lower(name));

CREATE TABLE professionals (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX professionals_name_key ON professionals (lower(name));

GRANT SELECT, INSERT ON procedures TO ${runtimeRole};
GRANT UPDATE (name, reference_price_cents, category, return_interval_days, active, updated_at,
    updated_by, version) ON procedures TO ${runtimeRole};
GRANT SELECT, INSERT ON professionals TO ${runtimeRole};
GRANT UPDATE (name, active, updated_at, updated_by, version) ON professionals TO ${runtimeRole};
