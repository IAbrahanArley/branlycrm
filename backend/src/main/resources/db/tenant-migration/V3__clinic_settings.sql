-- First clinic entity (CRM-015): one row of clinic settings, read and written through the clinic
-- persistence unit. Applied by the provisioner as the clinic owner role.
CREATE TABLE clinic_settings (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    -- IANA Area/Location name; the application validates it against the JDK zone database.
    time_zone VARCHAR(64) NOT NULL DEFAULT 'America/Sao_Paulo'
        CHECK (time_zone ~ '^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$'
               AND time_zone !~ '^(Etc|SystemV)/')
);

-- The row exists from the start; the runtime never inserts nor deletes it.
INSERT INTO clinic_settings DEFAULT VALUES;

GRANT SELECT ON clinic_settings TO ${runtimeRole};
GRANT UPDATE (time_zone) ON clinic_settings TO ${runtimeRole};
