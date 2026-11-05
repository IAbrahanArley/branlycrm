#!/bin/sh
set -eu
export PGHOST=postgres PGPORT=5432 PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD"
psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$POSTGRES_DB" <<'SQL'
\getenv runtime_password CONTROL_RUNTIME_PASSWORD
\getenv migration_password CONTROL_MIGRATION_PASSWORD
\getenv provisioner_password TENANT_PROVISIONER_PASSWORD
\getenv worker_password PROVISIONING_WORKER_PASSWORD
SELECT 'CREATE ROLE crm_control_runtime LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='crm_control_runtime') \gexec
SELECT 'CREATE ROLE crm_control_migrator LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='crm_control_migrator') \gexec
SELECT 'CREATE ROLE crm_tenant_provisioner LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='crm_tenant_provisioner') \gexec
SELECT 'CREATE ROLE crm_provisioning_worker LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='crm_provisioning_worker') \gexec
SELECT format('ALTER ROLE crm_control_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', :'runtime_password') \gexec
SELECT format('ALTER ROLE crm_control_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', :'migration_password') \gexec
SELECT format('ALTER ROLE crm_tenant_provisioner LOGIN NOSUPERUSER CREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', :'provisioner_password') \gexec
SELECT format('ALTER ROLE crm_provisioning_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', :'worker_password') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO crm_control_runtime, crm_control_migrator, crm_provisioning_worker', current_database()) \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM crm_tenant_provisioner', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO crm_control_runtime, crm_provisioning_worker;
GRANT USAGE, CREATE ON SCHEMA public TO crm_control_migrator;
-- V1 is historical and requires vector to exist before a non-superuser migrator runs.
CREATE EXTENSION IF NOT EXISTS vector;
-- Adopt only the application's existing tables/functions. Never REASSIGN OWNED globally.
SELECT format('ALTER TABLE public.%I OWNER TO crm_control_migrator', tablename)
FROM pg_tables WHERE schemaname='public' AND tablename IN
('flyway_schema_history','tenants','tenant_memberships','user_accounts') \gexec
SELECT format('ALTER FUNCTION %s OWNER TO crm_control_migrator', oid::regprocedure)
FROM pg_proc WHERE pronamespace='public'::regnamespace
AND proname IN ('protect_tenant_identity','update_account_version') \gexec
-- Clinic runtime roles must not reach maintenance databases; only the provisioner uses postgres.
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
GRANT CONNECT ON DATABASE postgres TO crm_tenant_provisioner;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
SQL
# vector is not a trusted extension: preparing it in template1 lets the non-superuser
# provisioner create clinic databases that already have it (tenant V1 skips creation).
psql --no-psqlrc --set=ON_ERROR_STOP=1 --dbname=template1   --command='CREATE EXTENSION IF NOT EXISTS vector'