-- WhatsApp channel connection and disconnection (CRM-081b, ADR-036). Additive:
--   * a DISCONNECT step for the worker;
--   * the number the person chose in the Embedded Signup, only a selector the worker checks
--     against the token's own account, and whether the number came from the WhatsApp Business
--     app (coexistence: no registration);
--   * at most one running task per channel.
-- Rollback: drop the two columns and the index, and restore the previous step check (no DISCONNECT
-- task may exist then).

ALTER TABLE whatsapp_onboarding_tasks DROP CONSTRAINT whatsapp_onboarding_tasks_step_check;
ALTER TABLE whatsapp_onboarding_tasks ADD CONSTRAINT whatsapp_onboarding_tasks_step_check
    CHECK (step IN ('VERIFY_ASSETS', 'REGISTER_NUMBER', 'SUBSCRIBE_WEBHOOKS', 'DONE',
                    'DISCONNECT'));

ALTER TABLE whatsapp_onboarding_tasks
    ADD COLUMN requested_phone_number_id VARCHAR(32)
        CHECK (requested_phone_number_id ~ '^[0-9]{1,32}$'),
    ADD COLUMN coexistence BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX whatsapp_onboarding_one_open_per_channel
    ON whatsapp_onboarding_tasks (channel_id) WHERE status IN ('PENDING', 'RUNNING');

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        -- The API creates connection and disconnection tasks, always PENDING by default.
        GRANT INSERT (requested_phone_number_id, coexistence) ON whatsapp_onboarding_tasks
            TO crm_control_runtime;
    END IF;
END;
$$;
