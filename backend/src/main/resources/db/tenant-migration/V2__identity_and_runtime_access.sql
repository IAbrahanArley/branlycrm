-- Applied by the provisioner while SET ROLE to the clinic owner role, never by the runtime role.
-- ${tenantId} and ${runtimeRole} are Flyway placeholders derived from the registry UUID.
INSERT INTO tenant_identity (tenant_id) VALUES ('${tenantId}');

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ${runtimeRole};

-- Runtime reads its identity; it never changes it nor the migration history.
GRANT SELECT ON tenant_identity TO ${runtimeRole};

-- Every later tenant migration grants the runtime role explicitly, table by table.
