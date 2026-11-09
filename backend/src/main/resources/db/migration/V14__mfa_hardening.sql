-- CRM-016, security review: progressive lockout with an attempt reserved before the comparison,
-- and account notices for events the API did not perform itself (platform reset of the factor).
-- V13 is already applied in some environments, so its functions are replaced here, never edited.

-- Consecutive locks: each new lock lasts twice the previous one (15 min, 30, 60... at most 24 h).
-- Only a successful code resets it.
ALTER TABLE user_mfa_totp
    ADD COLUMN consecutive_locks INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_locks >= 0);

-- Starts a lock on a row already locked FOR UPDATE by the caller. Returns its length in seconds.
-- Not executable by anyone: internal to the functions below.
CREATE FUNCTION mfa_start_lock(subject UUID) RETURNS BIGINT LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    locks INTEGER;
    duration INTERVAL;
BEGIN
    SELECT consecutive_locks INTO locks FROM user_mfa_totp WHERE subject_id = subject;
    duration := LEAST(interval '24 hours', interval '15 minutes' * power(2, LEAST(locks, 10)));
    UPDATE user_mfa_totp
    SET failed_attempts = 0, locked_until = now() + duration,
        consecutive_locks = consecutive_locks + 1
    WHERE subject_id = subject;
    RETURN ceil(extract(epoch FROM duration))::bigint;
END;
$$;
REVOKE EXECUTE ON FUNCTION mfa_start_lock(UUID) FROM PUBLIC;

-- Reserves one comparison before it happens, so concurrent requests can never compare more than
-- ten wrong codes per lock window. Refuses while locked; at the limit it starts the lock itself.
-- lock_started is true for exactly one call per lock window (the notice e-mail goes with it).
-- An account without a factor row has nothing to lock and is always allowed.
CREATE FUNCTION mfa_reserve_attempt(subject UUID,
                                    OUT allowed boolean, OUT retry_seconds BIGINT,
                                    OUT lock_started boolean)
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    current_row user_mfa_totp%ROWTYPE;
BEGIN
    allowed := false;
    retry_seconds := 0;
    lock_started := false;
    SELECT * INTO current_row FROM user_mfa_totp WHERE subject_id = subject FOR UPDATE;
    IF NOT FOUND THEN
        allowed := true;
        RETURN;
    END IF;
    IF current_row.locked_until > now() THEN
        retry_seconds := ceil(extract(epoch FROM current_row.locked_until - now()))::bigint;
        RETURN;
    END IF;
    IF current_row.failed_attempts >= 10 THEN
        retry_seconds := mfa_start_lock(subject);
        lock_started := true;
        RETURN;
    END IF;
    UPDATE user_mfa_totp SET failed_attempts = failed_attempts + 1 WHERE subject_id = subject;
    allowed := true;
END;
$$;

-- After a reserved comparison failed: the attempt already counts; the tenth starts the lock.
CREATE FUNCTION mfa_finish_failure(subject UUID,
                                   OUT locked boolean, OUT retry_seconds BIGINT,
                                   OUT lock_started boolean)
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    current_row user_mfa_totp%ROWTYPE;
BEGIN
    locked := false;
    retry_seconds := 0;
    lock_started := false;
    SELECT * INTO current_row FROM user_mfa_totp WHERE subject_id = subject FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF current_row.locked_until > now() THEN
        locked := true;
        retry_seconds := ceil(extract(epoch FROM current_row.locked_until - now()))::bigint;
        RETURN;
    END IF;
    IF current_row.failed_attempts >= 10 THEN
        locked := true;
        retry_seconds := mfa_start_lock(subject);
        lock_started := true;
    END IF;
END;
$$;

-- A success clears the reserved attempts and the consecutive locks.
CREATE OR REPLACE FUNCTION mfa_reset_failures(subject UUID)
RETURNS void LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE user_mfa_totp SET failed_attempts = 0, consecutive_locks = 0
    WHERE subject_id = subject AND (failed_attempts > 0 OR consecutive_locks > 0);
END;
$$;

REVOKE EXECUTE ON FUNCTION mfa_reserve_attempt(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION mfa_finish_failure(UUID) FROM PUBLIC;

-- Notices for the account about events the API did not perform, sent by the API at the next login
-- (the platform process has no mail credential). Written only by platform_reset_mfa; the API reads
-- them and marks them sent only through the functions below.
CREATE TABLE account_notices (
    notice_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id UUID NOT NULL REFERENCES user_accounts(subject_id) ON DELETE RESTRICT,
    kind VARCHAR(30) NOT NULL CHECK (kind IN ('MFA_RESET')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX account_notices_pending_idx ON account_notices(subject_id, created_at)
    WHERE sent_at IS NULL;

-- Claims a pending notice of that subject for sending: true for exactly one caller.
CREATE FUNCTION account_notice_claim(notice UUID, subject UUID) RETURNS boolean LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE account_notices SET sent_at = now()
    WHERE notice_id = notice AND subject_id = subject AND sent_at IS NULL;
    RETURN FOUND;
END;
$$;

-- Gives a claimed notice back when the e-mail failed, so the next login tries again.
CREATE FUNCTION account_notice_release(notice UUID, subject UUID) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    UPDATE account_notices SET sent_at = NULL
    WHERE notice_id = notice AND subject_id = subject AND sent_at IS NOT NULL;
    RETURN FOUND;
END;
$$;
REVOKE EXECUTE ON FUNCTION account_notice_claim(UUID, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION account_notice_release(UUID, UUID) FROM PUBLIC;

-- Same contract as V13, plus the notice for the account.
CREATE OR REPLACE FUNCTION platform_reset_mfa(target_email VARCHAR)
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
    INSERT INTO account_notices (subject_id, kind) VALUES (subject, 'MFA_RESET');
    RETURN COALESCE(had_factor, false);
END;
$$;

-- The runtime no longer counts failures after the comparison (V13 mfa_register_failure): the
-- reservation replaces it. Grants stay conditional; tests can omit deployment roles.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_control_runtime') THEN
        REVOKE EXECUTE ON FUNCTION mfa_register_failure(UUID, INTEGER, INTERVAL)
            FROM crm_control_runtime;
        GRANT EXECUTE ON FUNCTION mfa_reserve_attempt(UUID), mfa_finish_failure(UUID),
            account_notice_claim(UUID, UUID), account_notice_release(UUID, UUID)
            TO crm_control_runtime;
        GRANT SELECT (notice_id, subject_id, kind, created_at, sent_at)
            ON account_notices TO crm_control_runtime;
    END IF;
END;
$$;
