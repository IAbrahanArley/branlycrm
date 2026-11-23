-- Outbound signal of a channel (CRM-084a, ADR-036): the API raises it after queuing a reply, the
-- worker clears it once the clinic's outbox has nothing due, as inbound_pending_since does for
-- the inbox. It carries no content. Additive. Rollback: drop the column.

ALTER TABLE whatsapp_channels ADD COLUMN outbound_pending_since TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT SELECT (outbound_pending_since), UPDATE (outbound_pending_since)
            ON whatsapp_channels TO crm_control_runtime;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_whatsapp_worker') THEN
        GRANT UPDATE (outbound_pending_since) ON whatsapp_channels TO crm_whatsapp_worker;
    END IF;
END;
$$;
