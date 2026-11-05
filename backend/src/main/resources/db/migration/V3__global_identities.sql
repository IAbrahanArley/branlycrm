CREATE TABLE user_accounts (
    subject_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND email LIKE '%_@_%._%'),
    display_name VARCHAR(160) NOT NULL CHECK (length(btrim(display_name)) > 0),
    password_hash VARCHAR(255) NOT NULL CHECK (password_hash LIKE '{bcrypt}%'),
    active BOOLEAN NOT NULL DEFAULT true,
    email_verified BOOLEAN NOT NULL DEFAULT false,
    auth_version BIGINT NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE tenant_memberships ADD CONSTRAINT membership_subject_fk
    FOREIGN KEY (subject_id) REFERENCES user_accounts(subject_id) ON DELETE RESTRICT;

CREATE FUNCTION update_account_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.subject_id IS DISTINCT FROM OLD.subject_id THEN
        RAISE EXCEPTION 'Subject identity is immutable';
    END IF;
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.active IS DISTINCT FROM OLD.active
        OR NEW.email_verified IS DISTINCT FROM OLD.email_verified
        OR NEW.email IS DISTINCT FROM OLD.email THEN
        NEW.auth_version := OLD.auth_version + 1;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_version_guard BEFORE UPDATE ON user_accounts
FOR EACH ROW EXECUTE FUNCTION update_account_version();