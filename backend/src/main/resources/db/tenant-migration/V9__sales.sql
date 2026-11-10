-- Sales (CRM-035, CRM-05, RN-03, RN-04). Applied by the provisioner, or by the tenant-migrate
-- rollout, as the clinic owner role. Registering a sale closes its lead as won in the same
-- transaction; a cancelled sale leaves revenue and reports but the lead stays won. Nothing is
-- deleted. The lead carries the origin (CRM-090), so the sale inherits it through lead_id.

CREATE TABLE sales (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES leads (id) ON DELETE RESTRICT,
    -- The lead's contact, kept here for the patient history and reports.
    contact_id UUID NOT NULL REFERENCES contacts (id) ON DELETE RESTRICT,
    procedure_id UUID NOT NULL REFERENCES procedures (id) ON DELETE RESTRICT,
    professional_id UUID REFERENCES professionals (id) ON DELETE RESTRICT,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0 AND amount_cents <= 1000000000),
    -- Calendar day of the sale in the clinic's time zone.
    sold_on DATE NOT NULL,
    payment_method VARCHAR(16) CHECK (payment_method IN (
        'PIX', 'CASH', 'CREDIT_CARD', 'DEBIT_CARD', 'BANK_SLIP', 'TRANSFER', 'OTHER')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    cancel_reason VARCHAR(500) CHECK (btrim(cancel_reason) <> ''),
    cancelled_at TIMESTAMPTZ,
    cancelled_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT sales_cancelled_check CHECK (
        (status = 'CANCELLED')
        = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT sales_cancel_fields_check CHECK (
        status = 'CANCELLED'
        OR (cancelled_at IS NULL AND cancelled_by IS NULL AND cancel_reason IS NULL))
);

-- A lead is won by one active sale.
CREATE UNIQUE INDEX sales_one_active_per_lead ON sales (lead_id) WHERE status = 'ACTIVE';
CREATE INDEX sales_by_contact ON sales (contact_id, sold_on DESC);
CREATE INDEX sales_by_day ON sales (sold_on DESC) WHERE status = 'ACTIVE';

-- Append-only history of each sale: the values before and after each change (RN-09).
CREATE TABLE sale_events (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sales (id) ON DELETE RESTRICT,
    type VARCHAR(12) NOT NULL CHECK (type IN ('CREATED', 'CORRECTED', 'CANCELLED')),
    procedure_id UUID,
    professional_id UUID,
    amount_cents BIGINT,
    sold_on DATE,
    payment_method VARCHAR(16) CHECK (payment_method IN (
        'PIX', 'CASH', 'CREDIT_CARD', 'DEBIT_CARD', 'BANK_SLIP', 'TRANSFER', 'OTHER')),
    previous_procedure_id UUID,
    previous_professional_id UUID,
    previous_amount_cents BIGINT,
    previous_sold_on DATE,
    previous_payment_method VARCHAR(16) CHECK (previous_payment_method IN (
        'PIX', 'CASH', 'CREDIT_CARD', 'DEBIT_CARD', 'BANK_SLIP', 'TRANSFER', 'OTHER')),
    reason VARCHAR(500),
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    seq BIGINT GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX sale_events_by_sale ON sale_events (sale_id, seq);

-- "Patient" is a contact with at least one active sale; kept by the sale transactions.
ALTER TABLE contacts ADD COLUMN patient_since TIMESTAMPTZ;

GRANT SELECT, INSERT ON sales TO ${runtimeRole};
GRANT UPDATE (procedure_id, professional_id, amount_cents, sold_on, payment_method, status,
              cancel_reason, cancelled_at, cancelled_by, updated_at, updated_by, version)
    ON sales TO ${runtimeRole};
GRANT SELECT, INSERT ON sale_events TO ${runtimeRole};
GRANT UPDATE (patient_since) ON contacts TO ${runtimeRole};
