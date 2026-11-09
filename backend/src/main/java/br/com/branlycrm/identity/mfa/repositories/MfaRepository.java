package br.com.branlycrm.identity.mfa.repositories;

import br.com.branlycrm.identity.mfa.domain.MfaAttempt;
import br.com.branlycrm.identity.mfa.domain.MfaFactor;
import br.com.branlycrm.identity.mfa.domain.MfaRequirement;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Second-factor rows of the control database (V13). The runtime reads columns and writes only
 * through the mfa_* functions, which hold the rules (single use, lockout, nonce match). Its own SQL
 * for the obligation, so this module never depends on the agency module.
 */
@Repository
public class MfaRepository {
  private final JdbcTemplate jdbc;

  public MfaRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Obligation: an active OWNER membership in any clinic, whatever its status, or agency admin. */
  public MfaRequirement requirement(UUID subject) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS (SELECT 1 FROM user_mfa_totp
                       WHERE subject_id = ? AND confirmed_at IS NOT NULL) AS enrolled,
               EXISTS (SELECT 1 FROM tenant_memberships
                       WHERE subject_id = ? AND role = 'OWNER' AND active)
               OR EXISTS (SELECT 1 FROM agency_memberships
                          WHERE subject_id = ? AND active) AS required
        """,
        (row, index) -> new MfaRequirement(row.getBoolean("enrolled"), row.getBoolean("required")),
        subject,
        subject,
        subject);
  }

  public Optional<MfaFactor> find(UUID subject) {
    return jdbc
        .query(
            """
            SELECT subject_id, key_id, secret_ciphertext, secret_nonce, pending_key_id,
                   pending_ciphertext, pending_nonce,
                   pending_created_at > now() - interval '10 minutes' AS pending_fresh,
                   CASE WHEN locked_until > now()
                        THEN ceil(extract(epoch FROM locked_until - now()))::bigint
                        ELSE 0 END AS locked_seconds
            FROM user_mfa_totp WHERE subject_id = ?
            """,
            (row, index) ->
                new MfaFactor(
                    row.getObject("subject_id", UUID.class),
                    sealed(row, "key_id", "secret_ciphertext", "secret_nonce"),
                    sealed(row, "pending_key_id", "pending_ciphertext", "pending_nonce"),
                    row.getBoolean("pending_fresh"),
                    row.getLong("locked_seconds")),
            subject)
        .stream()
        .findFirst();
  }

  public long remainingRecoveryCodes(UUID subject) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_mfa_recovery_codes WHERE subject_id = ? AND used_at IS NULL",
            Long.class,
            subject);
    return count == null ? 0 : count;
  }

  public void stage(UUID subject, SealedSecret pending) {
    jdbc.queryForObject(
        "SELECT mfa_stage_secret(?, ?, ?, ?)::text",
        String.class,
        subject,
        pending.keyId(),
        pending.ciphertext(),
        pending.nonce());
  }

  /** CONFIRMED, REPLACED or EXPIRED (see V13). */
  public String confirm(UUID subject, byte[] expectedNonce, long step, List<byte[]> hashes) {
    return jdbc.execute(
        (ConnectionCallback<String>)
            connection -> {
              try (var statement =
                  connection.prepareStatement("SELECT mfa_confirm_secret(?, ?, ?, ?)")) {
                statement.setObject(1, subject);
                statement.setBytes(2, expectedNonce);
                statement.setLong(3, step);
                statement.setArray(4, byteaArray(connection, hashes));
                try (ResultSet result = statement.executeQuery()) {
                  result.next();
                  return result.getString(1);
                }
              }
            });
  }

  public boolean consumeStep(UUID subject, byte[] expectedNonce, long step) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT mfa_consume_step(?, ?, ?)", Boolean.class, subject, expectedNonce, step));
  }

  public boolean consumeRecovery(UUID subject, byte[] hash) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT mfa_consume_recovery(?, ?)", Boolean.class, subject, hash));
  }

  /** Reserves one comparison before it happens (V14 mfa_reserve_attempt). */
  public MfaAttempt reserveAttempt(UUID subject) {
    return jdbc.queryForObject(
        "SELECT allowed, retry_seconds, lock_started FROM mfa_reserve_attempt(?)",
        (row, index) ->
            new MfaAttempt(
                row.getBoolean("allowed"),
                row.getLong("retry_seconds"),
                row.getBoolean("lock_started")),
        subject);
  }

  /** After a reserved comparison failed; {@code allowed} is false when the account is locked. */
  public MfaAttempt finishFailure(UUID subject) {
    return jdbc.queryForObject(
        "SELECT locked, retry_seconds, lock_started FROM mfa_finish_failure(?)",
        (row, index) ->
            new MfaAttempt(
                !row.getBoolean("locked"),
                row.getLong("retry_seconds"),
                row.getBoolean("lock_started")),
        subject);
  }

  public void resetFailures(UUID subject) {
    jdbc.queryForObject("SELECT mfa_reset_failures(?)::text", String.class, subject);
  }

  public boolean replaceRecoveryCodes(UUID subject, List<byte[]> hashes) {
    return Boolean.TRUE.equals(
        jdbc.execute(
            (ConnectionCallback<Boolean>)
                connection -> {
                  try (var statement =
                      connection.prepareStatement("SELECT mfa_replace_recovery_codes(?, ?)")) {
                    statement.setObject(1, subject);
                    statement.setArray(2, byteaArray(connection, hashes));
                    try (ResultSet result = statement.executeQuery()) {
                      result.next();
                      return result.getBoolean(1);
                    }
                  }
                }));
  }

  public boolean disable(UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT mfa_disable(?)", Boolean.class, subject));
  }

  private static Array byteaArray(Connection connection, List<byte[]> values) throws SQLException {
    return connection.createArrayOf("bytea", values.toArray(new byte[0][]));
  }

  private static SealedSecret sealed(ResultSet row, String keyId, String ciphertext, String nonce)
      throws SQLException {
    byte[] data = row.getBytes(ciphertext);
    if (data == null) return null;
    return new SealedSecret(row.getInt(keyId), data, row.getBytes(nonce));
  }
}
