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
 IF has_table_privilege(current_user,'tenant_memberships','DELETE') THEN RAISE EXCEPTION 'Runtime can delete memberships'; END IF;
 IF has_column_privilege(current_user,'tenant_memberships','tenant_id','UPDATE') OR has_column_privilege(current_user,'tenant_memberships','subject_id','UPDATE') THEN
   RAISE EXCEPTION 'Runtime can move memberships between clinics or subjects';
 END IF;
 IF NOT has_column_privilege(current_user,'tenant_memberships','role','UPDATE') THEN RAISE EXCEPTION 'Runtime cannot change roles'; END IF;
 -- V8 (ADR-028): clinics only through allowed columns, no status or agency writes, no direct
 -- account or membership writes, activation only through activate_awaiting_clinic.
 IF has_column_privilege(current_user,'tenants','status','UPDATE') OR has_column_privilege(current_user,'tenants','status','INSERT') THEN
   RAISE EXCEPTION 'Runtime can set clinic status';
 END IF;
 IF has_column_privilege(current_user,'tenants','agency_id','UPDATE') THEN RAISE EXCEPTION 'Runtime can move clinics between agencies'; END IF;
 IF has_table_privilege(current_user,'tenants','DELETE') THEN RAISE EXCEPTION 'Runtime can delete clinics'; END IF;
 IF has_table_privilege(current_user,'user_accounts','INSERT') OR has_table_privilege(current_user,'user_accounts','UPDATE') OR has_table_privilege(current_user,'user_accounts','DELETE') THEN
   RAISE EXCEPTION 'Runtime can write accounts directly';
 END IF;
 IF has_table_privilege(current_user,'tenant_memberships','INSERT') THEN RAISE EXCEPTION 'Runtime can add memberships directly'; END IF;
 -- V9 (ADR-028): the invitation acceptance writes only these columns.
 IF EXISTS (SELECT 1 FROM information_schema.column_privileges
            WHERE grantee = current_user AND table_name = 'user_accounts'
              AND ((privilege_type = 'INSERT' AND column_name NOT IN ('email', 'display_name', 'password_hash', 'email_verified'))
                OR privilege_type = 'UPDATE')) THEN
   RAISE EXCEPTION 'Runtime can write account columns beyond the invitation flow';
 END IF;
 -- V10: unverified accounts are claimed only through this function.
 IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'claim_unverified_account' AND prosecdef
                AND proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp'])
    OR has_function_privilege('public','claim_unverified_account(uuid,varchar,varchar)','EXECUTE')
    OR NOT has_function_privilege(current_user,'claim_unverified_account(uuid,varchar,varchar)','EXECUTE') THEN
   RAISE EXCEPTION 'claim_unverified_account is missing, not restricted or not available to the runtime';
 END IF;
 IF EXISTS (SELECT 1 FROM information_schema.column_privileges
            WHERE grantee = current_user AND table_name = 'invitations'
              AND ((privilege_type = 'INSERT' AND column_name NOT IN ('token_hash', 'email', 'kind', 'tenant_id', 'agency_id', 'role', 'invited_by', 'issuer_agency_id', 'expires_at', 'send_count', 'last_sent_at'))
                OR (privilege_type = 'UPDATE' AND column_name NOT IN ('token_hash', 'role', 'invited_by', 'expires_at', 'accepted_at', 'accepted_by', 'revoked_at', 'revoked_by', 'send_count', 'last_sent_at')))) THEN
   RAISE EXCEPTION 'Runtime can write invitation columns beyond the invitation flow';
 END IF;
 IF EXISTS (SELECT 1 FROM information_schema.column_privileges
            WHERE grantee = current_user AND table_name = 'tenant_memberships' AND privilege_type = 'INSERT'
              AND column_name NOT IN ('tenant_id', 'subject_id', 'role')) THEN
   RAISE EXCEPTION 'Runtime can insert membership columns beyond the invitation flow';
 END IF;
 IF has_table_privilege(current_user,'invitations','DELETE')
    OR has_column_privilege(current_user,'invitations','email','UPDATE')
    OR has_column_privilege(current_user,'invitations','tenant_id','UPDATE')
    OR has_column_privilege(current_user,'invitations','issuer_agency_id','UPDATE') THEN
   RAISE EXCEPTION 'Runtime can delete invitations or change their target';
 END IF;
 IF EXISTS (SELECT 1 FROM information_schema.column_privileges
            WHERE grantee = current_user AND table_name = 'tenants' AND privilege_type = 'UPDATE'
              AND column_name <> 'updated_at') THEN
   RAISE EXCEPTION 'Runtime can update clinic columns other than updated_at';
 END IF;
 IF EXISTS (SELECT 1 FROM information_schema.column_privileges
            WHERE grantee = current_user AND table_name = 'tenants' AND privilege_type = 'INSERT') THEN
   RAISE EXCEPTION 'Runtime can create clinics before the agency service exists';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'activate_awaiting_clinic' AND prosecdef
                AND proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp']) THEN
   RAISE EXCEPTION 'activate_awaiting_clinic lost SECURITY DEFINER or its fixed search_path';
 END IF;
 IF NOT has_function_privilege(current_user,'activate_awaiting_clinic(uuid)','EXECUTE') THEN RAISE EXCEPTION 'Runtime cannot activate awaiting clinics'; END IF;
 IF has_function_privilege('public','activate_awaiting_clinic(uuid)','EXECUTE') THEN RAISE EXCEPTION 'PUBLIC can activate clinics'; END IF;
END $$;
SELECT count(*) >= 0 AS application_tables_accessible FROM tenants;
ROLLBACK;
SQL
