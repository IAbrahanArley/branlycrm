-- Indexes for the monthly report and the reception dashboard (CRM-051, docs/metricas.md). Applied
-- by the provisioner, or by the tenant-migrate rollout, as the clinic owner role. No grant changes:
-- the report only reads. Plain CREATE INDEX locks writes on contacts and leads while it builds,
-- which is short at MVP sizes; rollback is DROP INDEX of the three below.

CREATE INDEX contacts_by_created ON contacts (created_at);
CREATE INDEX leads_by_opened ON leads (opened_at);
CREATE INDEX leads_closed_by_day ON leads (closed_at) WHERE status <> 'OPEN';
