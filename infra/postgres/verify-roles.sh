#!/bin/sh
set -eu
export PGHOST=postgres PGPORT=5432 PGUSER=crm_control_runtime PGPASSWORD="$CONTROL_RUNTIME_PASSWORD"
psql --no-psqlrc -v ON_ERROR_STOP=1 --dbname="$POSTGRES_DB" <<'SQL'
BEGIN;
DO $$
DECLARE rejected boolean := false;
BEGIN
 IF current_user <> 'crm_control_runtime' THEN RAISE EXCEPTION 'Wrong runtime role'; END IF;
 IF EXISTS (SELECT FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls)) THEN
   RAISE EXCEPTION 'Runtime role has elevated privileges';
 END IF;
 BEGIN CREATE TABLE public.runtime_forbidden_test(id integer);
 EXCEPTION WHEN insufficient_privilege THEN rejected := true;
 END;
 IF NOT rejected THEN RAISE EXCEPTION 'Runtime unexpectedly created table'; END IF;
 rejected := false;
 BEGIN CREATE ROLE runtime_forbidden_test;
 EXCEPTION WHEN insufficient_privilege THEN rejected := true;
 END;
 IF NOT rejected THEN RAISE EXCEPTION 'Runtime unexpectedly created role'; END IF;
 IF has_table_privilege(current_user,'flyway_schema_history','UPDATE') THEN RAISE EXCEPTION 'Runtime can alter migration history'; END IF;
 IF has_database_privilege('crm_tenant_provisioner',current_database(),'CONNECT') THEN RAISE EXCEPTION 'Provisioner can access control database'; END IF;
 IF has_column_privilege('crm_provisioning_worker','user_accounts','password_hash','SELECT') THEN RAISE EXCEPTION 'Worker can read password hashes'; END IF;
 IF has_database_privilege('public','postgres','CONNECT') OR has_database_privilege('public','template1','CONNECT') THEN
   RAISE EXCEPTION 'PUBLIC can connect to maintenance databases';
 END IF;
 IF NOT has_database_privilege('crm_tenant_provisioner','postgres','CONNECT') THEN RAISE EXCEPTION 'Provisioner cannot reach maintenance database'; END IF;
END $$;
SELECT count(*) >= 0 AS application_tables_accessible FROM tenants;
ROLLBACK;
SQL
