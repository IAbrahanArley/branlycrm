-- Security review of the agency portfolio (ADR-028). Column grants apply to every row, so V11
-- let crm_platform_operator read the addresses of all invitations and rotate the token of any
-- clinic invitation, which would make it the owner of a clinic. The operator now has no direct
-- privilege on invitations: it issues agency admin invitations only through this function, with
-- kind fixed to AGENCY and the agency required to be ACTIVE.

REVOKE SELECT, INSERT, UPDATE ON invitations FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_platform_operator') THEN
        REVOKE SELECT (invitation_id, kind, agency_id, email, role, accepted_at, revoked_at,
                       send_count)
            ON invitations FROM crm_platform_operator;
        REVOKE INSERT (token_hash, email, kind, agency_id, role, expires_at, send_count,
                       last_sent_at)
            ON invitations FROM crm_platform_operator;
        REVOKE UPDATE (token_hash, expires_at, send_count, last_sent_at)
            ON invitations FROM crm_platform_operator;
    END IF;
END;
$$;

-- Issues or rotates the pending agency admin invitation of an address. Rotating replaces the
-- token, so the previous link stops working (also the way to kill a leaked link).
CREATE FUNCTION platform_issue_agency_admin_invitation(
    target_agency UUID, invited_email VARCHAR, new_token_hash BYTEA, new_expires_at TIMESTAMPTZ)
RETURNS UUID LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    pending UUID;
BEGIN
    PERFORM 1 FROM agencies WHERE agency_id = target_agency AND status = 'ACTIVE';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Agency not found or suspended' USING ERRCODE = 'BRL02';
    END IF;
    SELECT invitation_id INTO pending FROM invitations
    WHERE kind = 'AGENCY' AND agency_id = target_agency AND email = invited_email
      AND accepted_at IS NULL AND revoked_at IS NULL
    FOR UPDATE;
    IF FOUND THEN
        UPDATE invitations
        SET token_hash = new_token_hash, expires_at = new_expires_at,
            send_count = send_count + 1, last_sent_at = now()
        WHERE invitation_id = pending;
        RETURN pending;
    END IF;
    INSERT INTO invitations (token_hash, email, kind, agency_id, role, expires_at, send_count,
                             last_sent_at)
    VALUES (new_token_hash, invited_email, 'AGENCY', target_agency, 'AGENCY_ADMIN',
            new_expires_at, 1, now())
    RETURNING invitation_id INTO pending;
    RETURN pending;
END;
$$;
REVOKE EXECUTE ON FUNCTION platform_issue_agency_admin_invitation(UUID, VARCHAR, BYTEA, TIMESTAMPTZ)
    FROM PUBLIC;

-- The API never issues agency admin invitations (ADR-028: only the platform does). The runtime's
-- column grant on invitations.kind applies to every row, so the rule lives in a trigger. The
-- function above runs as its owner, not as the runtime, so it is not affected.
CREATE FUNCTION refuse_runtime_agency_invitations() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NEW.kind = 'AGENCY' AND current_user = 'crm_control_runtime' THEN
        RAISE EXCEPTION 'Agency invitations are issued by the platform only' USING ERRCODE = 'BRL02';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invitations_agency_issuer_guard BEFORE INSERT ON invitations
FOR EACH ROW EXECUTE FUNCTION refuse_runtime_agency_invitations();

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'crm_platform_operator') THEN
        GRANT EXECUTE ON FUNCTION
            platform_issue_agency_admin_invitation(UUID, VARCHAR, BYTEA, TIMESTAMPTZ)
            TO crm_platform_operator;
    END IF;
END;
$$;
