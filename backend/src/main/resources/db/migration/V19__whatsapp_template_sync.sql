-- Template synchronization of a channel (CRM-085, ADR-036). The worker copies the approved message
-- templates of the channel's WhatsApp Business Account into the clinic database: when the channel
-- has never been synced, every 6 hours, and when requested (a template webhook event or the
-- clinic's "sync now"). The columns carry no content. Additive. Rollback: drop the columns.

ALTER TABLE whatsapp_channels
    ADD COLUMN templates_sync_requested_at TIMESTAMPTZ,
    ADD COLUMN templates_synced_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT SELECT (templates_sync_requested_at, templates_synced_at),
              UPDATE (templates_sync_requested_at)
            ON whatsapp_channels TO crm_control_runtime;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_whatsapp_worker') THEN
        GRANT UPDATE (templates_sync_requested_at, templates_synced_at)
            ON whatsapp_channels TO crm_whatsapp_worker;
    END IF;
END;
$$;
