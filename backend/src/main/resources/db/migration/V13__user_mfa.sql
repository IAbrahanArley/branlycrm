-- CRM-016: TOTP second factor (RFC 6238) for global identities. Mandatory for clinic owners and
-- agency admins, optional for everyone else; the state is derived on every request, never stored.
-- The secret is kept only encrypted (AES-256-GCM in the API, with a key the database never sees),
-- recovery codes only as HMAC-SHA-256. The runtime reads the columns it needs and changes these
-- tables exclusively through the SECURITY DEFINER functions below, each with its rule inside.

CREATE TABLE user_mfa_totp (
    subject_id UUID PRIMARY KEY REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    -- Confirmed factor: key_id names the API encryption key, the ciphertext carries the GCM tag.
    key_id SMALLINT CHECK (key_id > 0),
    secret_ciphertext BYTEA CHECK (octet_length(secret_ciphertext) = 36),
    secret_nonce BYTEA CHECK (octet_length(secret_nonce) = 12),
    confirmed_at TIMESTAMPTZ,
    -- Secret staged by a setup and not confirmed yet; the confirmed one keeps working meanwhile.
    pending_key_id SMALLINT CHECK (pending_key_id > 0),
    pending_ciphertext BYTEA CHECK (octet_length(pending_ciphertext) = 36),
    pending_nonce BYTEA CHECK (octet_length(pending_nonce) = 12),
    pending_created_at TIMESTAMPTZ,
    -- Highest accepted time step: a code is never accepted twice (RFC 6238, section 5.2).
    last_used_step BIGINT CHECK (last_used_step >= 0),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nulls(key_id, secret_ciphertext, secret_nonce, confirmed_at) IN (0, 4)),
    CHECK (num_nulls(pending_key_id, pending_ciphertext, pending_nonce, pending_created_at) IN (0, 4))
);
CREATE TRIGGER user_mfa_totp_touch BEFORE UPDATE ON user_mfa_totp
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE user_mfa_recovery_codes (
    subject_id UUID NOT NULL REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    code_hash BYTEA NOT NULL CHECK (octet_length(code_hash) = 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at TIMESTAMPTZ,
    PRIMARY KEY (subject_id, code_hash)
);

-- Lock order in every function: user_mfa_totp, then user_mfa_recovery_codes, then user_accounts.
-- auth_version is bumped explicitly: the V3 trigger only reacts to password, e-mail, active and
-- verification changes. Bumping it revokes every session of the account on its next request.

-- Stages a new secret (setup). Replaces any earlier pending one, so a concurrent setup makes the
-- other confirm fail on the nonce.
CREATE FUNCTION mfa_stage_secret(subject UUID, new_key_id INTEGER, ciphertext BYTEA, nonce BYTEA)
RETURNS void LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    INSERT INTO user_mfa_totp (subject_id, pending_key_id, pending_ciphertext, pending_nonce,
                               pending_created_at)
    VALUES (subject, new_key_id, ciphertext, nonce, now())
    ON CONFLICT (subject_id) DO UPDATE
    SET pending_key_id = EXCLUDED.pending_key_id,
        pending_ciphertext = EXCLUDED.pending_ciphertext,
        pending_nonce = EXCLUDED.pending_nonce,
        pending_created_at = EXCLUDED.pending_created_at;
END;
$$;

-- Activates exactly the pending secret the API verified (same nonce, staged less than 10 minutes
-- ago) and replaces the recovery codes. A replacement of a confirmed factor bumps auth_version.
-- The step of the confirming code becomes the last used one, so it never serves for a login.
-- Returns CONFIRMED, REPLACED or EXPIRED (no such pending secret anymore).
CREATE FUNCTION mfa_confirm_secret(subject UUID, expected_nonce BYTEA, used_step BIGINT,
                                   code_hashes BYTEA[])
RETURNS VARCHAR LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    current_row user_mfa_totp%ROWTYPE;
BEGIN
    IF code_hashes IS NULL OR cardinality(code_hashes) <> 10 OR used_step IS NULL THEN
        RAISE EXCEPTION 'Ten recovery codes and a step are required' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_row FROM user_mfa_totp WHERE subject_id = subject FOR UPDATE;
    IF NOT FOUND OR current_row.pending_nonce IS NULL OR expected_nonce IS NULL
        OR current_row.pending_nonce IS DISTINCT FROM expected_nonce
        OR current_row.pending_created_at <= now() - interval '10 minutes' THEN
        RETURN 'EXPIRED';
    END IF;
    UPDATE user_mfa_totp
    SET key_id = pending_key_id, secret_ciphertext = pending_ciphertext,
        secret_nonce = pending_nonce, confirmed_at = now(),
        pending_key_id = NULL, pending_ciphertext = NULL, pending_nonce = NULL,
        pending_created_at = NULL,
        last_used_step = GREATEST(COALESCE(last_used_step, used_step), used_step),
        failed_attempts = 0
    WHERE subject_id = subject;
    DELETE FROM user_mfa_recovery_codes WHERE subject_id = subject;
    INSERT INTO user_mfa_recovery_codes (subject_id, code_hash)
    SELECT subject, hash FROM unnest(code_hashes) AS hash;
    IF current_row.confirmed_at IS NOT NULL THEN
        UPDATE user_accounts SET auth_version = auth_version + 1 WHERE subject_id = subject;
        RETURN 'REPLACED';
    END IF;
    RETURN 'CONFIRMED';
END;
$$;

-- Accepts a time step of the confirmed secret the API verified (same nonce) only once and only
-- above the last accepted one; the conditional UPDATE makes two concurrent uses yield one success.
CREATE FUNCTION mfa_consume_step(subject UUID, expected_nonce BYTEA, used_step BIGINT)
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE user_mfa_totp SET last_used_step = used_step
    WHERE subject_id = subject AND confirmed_at IS NOT NULL AND secret_nonce = expected_nonce
      AND (last_used_step IS NULL OR last_used_step < used_step)
      AND (locked_until IS NULL OR locked_until <= now());
    RETURN FOUND;
END;
$$;

-- Single use of a recovery code, also under concurrency, and only with a confirmed factor.
CREATE FUNCTION mfa_consume_recovery(subject UUID, hash BYTEA)
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE user_mfa_recovery_codes SET used_at = now()
    WHERE subject_id = subject AND code_hash = hash AND used_at IS NULL
      AND EXISTS (SELECT 1 FROM user_mfa_totp t
                  WHERE t.subject_id = subject AND t.confirmed_at IS NOT NULL
                    AND (t.locked_until IS NULL OR t.locked_until <= now()));
    RETURN FOUND;
END;
$$;

-- Counts a wrong code atomically; at max_failures the subject is locked for lock_interval and the
-- count starts again. Returns the end of a lock it just set, NULL otherwise. Bounded, so a caller
-- cannot turn the limit off.
CREATE FUNCTION mfa_register_failure(subject UUID, max_failures INTEGER, lock_interval INTERVAL)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    attempts INTEGER;
    until TIMESTAMPTZ;
BEGIN
    IF max_failures IS NULL OR max_failures NOT BETWEEN 1 AND 100
        OR lock_interval IS NULL OR lock_interval < interval '1 minute'
        OR lock_interval > interval '1 day' THEN
        RAISE EXCEPTION 'Failure limit out of bounds' USING ERRCODE = '22023';
    END IF;
    UPDATE user_mfa_totp SET failed_attempts = failed_attempts + 1
    WHERE subject_id = subject
    RETURNING failed_attempts INTO attempts;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF attempts >= max_failures THEN
        UPDATE user_mfa_totp SET failed_attempts = 0, locked_until = now() + lock_interval
        WHERE subject_id = subject
        RETURNING locked_until INTO until;
        RETURN until;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION mfa_reset_failures(subject UUID)
RETURNS void LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE user_mfa_totp SET failed_attempts = 0 WHERE subject_id = subject AND failed_attempts > 0;
END;
$$;

-- New recovery codes for a confirmed factor; the previous ones, used or not, stop working.
CREATE FUNCTION mfa_replace_recovery_codes(subject UUID, code_hashes BYTEA[])
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF code_hashes IS NULL OR cardinality(code_hashes) <> 10 THEN
        RAISE EXCEPTION 'Ten recovery codes are required' USING ERRCODE = '22023';
    END IF;
    PERFORM 1 FROM user_mfa_totp
    WHERE subject_id = subject AND confirmed_at IS NOT NULL
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    DELETE FROM user_mfa_recovery_codes WHERE subject_id = subject;
    INSERT INTO user_mfa_recovery_codes (subject_id, code_hash)
    SELECT subject, hash FROM unnest(code_hashes) AS hash;
    RETURN true;
END;
$$;

-- Removes the factor and its codes and revokes every session. Whether the user may do it
-- (optional factor, valid code) is decided by the API before calling.
CREATE FUNCTION mfa_disable(subject UUID)
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    had_factor boolean;
BEGIN
    DELETE FROM user_mfa_totp WHERE subject_id = subject
    RETURNING confirmed_at IS NOT NULL INTO had_factor;
    DELETE FROM user_mfa_recovery_codes WHERE subject_id = subject;
    IF had_factor THEN
        UPDATE user_accounts SET auth_version = auth_version + 1 WHERE subject_id = subject;
    END IF;
    RETURN COALESCE(had_factor, false);
END;
$$;

-- Platform recovery of an account that lost both the authenticator and the recovery codes
-- (reset-mfa command). Removes the factor and the codes and revokes every session; the next login
-- enrolls again. Returns whether a confirmed factor existed; an unknown address also returns false.
CREATE FUNCTION platform_reset_mfa(target_email VARCHAR)
RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    subject UUID;
    had_factor boolean;
BEGIN
    SELECT subject_id INTO subject FROM user_accounts WHERE email = target_email;
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    DELETE FROM user_mfa_totp WHERE subject_id = subject
    RETURNING confirmed_at IS NOT NULL INTO had_factor;
    DELETE FROM user_mfa_recovery_codes WHERE subject_id = subject;
    UPDATE user_accounts SET auth_version = auth_version + 1 WHERE subject_id = subject;
    RETURN COALESCE(had_factor, false);
END;
$$;

REVOKE EXECUTE ON FUNCTION mfa_stage_secret(UUID, INTEGER, BYTEA, BYTEA) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_confirm_secret(UUID, BYTEA, BIGINT, BYTEA[]) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_consume_step(UUID, BYTEA, BIGINT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_consume_recovery(UUID, BYTEA) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_register_failure(UUID, INTEGER, INTERVAL) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_reset_failures(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_replace_recovery_codes(UUID, BYTEA[]) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_disable(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION platform_reset_mfa(VARCHAR) FROM PUBLIC;

-- The runtime reads what the API needs (never the recovery code hashes, never the counters it
-- could reset) and writes only through the functions. The platform operator reads nothing here:
-- it may only call platform_reset_mfa. Tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        GRANT SELECT (subject_id, key_id, secret_ciphertext, secret_nonce, confirmed_at,
                      pending_key_id, pending_ciphertext, pending_nonce, pending_created_at,
                      locked_until)
            ON user_mfa_totp TO crm_control_runtime;
        GRANT SELECT (subject_id, used_at) ON user_mfa_recovery_codes TO crm_control_runtime;
        GRANT EXECUTE ON FUNCTION
            mfa_stage_secret(UUID, INTEGER, BYTEA, BYTEA),
            mfa_confirm_secret(UUID, BYTEA, BIGINT, BYTEA[]),
            mfa_consume_step(UUID, BYTEA, BIGINT),
            mfa_consume_recovery(UUID, BYTEA),
            mfa_register_failure(UUID, INTEGER, INTERVAL),
            mfa_reset_failures(UUID),
            mfa_replace_recovery_codes(UUID, BYTEA[]),
            mfa_disable(UUID)
            TO crm_control_runtime;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_platform_operator') THEN
        GRANT EXECUTE ON FUNCTION platform_reset_mfa(VARCHAR) TO crm_platform_operator;
    END IF;
END;
$$;
