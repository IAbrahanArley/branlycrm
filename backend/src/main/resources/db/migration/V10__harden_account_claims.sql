-- ADR-028, security review of the invitation flow: the API must not be able to overwrite the
-- password or the verification of any account. V9 granted UPDATE on those columns to the whole
-- table; the only legitimate write is taking over an active, unverified account with a valid
-- invitation token, so that becomes a SECURITY DEFINER function with the rule inside it.

CREATE FUNCTION claim_unverified_account(subject UUID, new_display_name VARCHAR, new_password_hash VARCHAR)
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF new_password_hash IS NULL OR new_password_hash NOT LIKE '{bcrypt}%' THEN
        RETURN false;
    END IF;
    -- Verified or inactive accounts are never touched. The V3 trigger bumps auth_version, which
    -- revokes any session opened with the previous password.
    UPDATE user_accounts
    SET display_name = new_display_name, password_hash = new_password_hash, email_verified = true
    WHERE subject_id = subject AND active AND NOT email_verified;
    RETURN FOUND;
END;
$$;
REVOKE EXECUTE ON FUNCTION claim_unverified_account(UUID, VARCHAR, VARCHAR) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        REVOKE UPDATE (display_name, password_hash, email_verified)
            ON user_accounts FROM crm_control_runtime;
        GRANT EXECUTE ON FUNCTION claim_unverified_account(UUID, VARCHAR, VARCHAR)
            TO crm_control_runtime;
    END IF;
END;
$$;
