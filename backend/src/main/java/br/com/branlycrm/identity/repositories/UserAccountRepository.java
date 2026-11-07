package br.com.branlycrm.identity.repositories;

import br.com.branlycrm.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Identity keeps JDBC: authentication reads are small and must stay explicit. */
@Repository
public class UserAccountRepository {
  private static final String COLUMNS =
      "SELECT subject_id, email, display_name, password_hash, active, email_verified, auth_version"
          + " FROM user_accounts";

  private static final RowMapper<UserAccount> ACCOUNT =
      (row, index) ->
          new UserAccount(
              row.getObject("subject_id", UUID.class),
              row.getString("email"),
              row.getString("display_name"),
              row.getString("password_hash"),
              row.getBoolean("active"),
              row.getBoolean("email_verified"),
              row.getLong("auth_version"));

  private final JdbcTemplate jdbc;

  public UserAccountRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Expects the normalized address; normalization belongs to the service. */
  public Optional<UserAccount> findByEmail(String email) {
    return jdbc.query(COLUMNS + " WHERE email = ?", ACCOUNT, email).stream().findFirst();
  }

  public Optional<UserAccount> findById(UUID subjectId) {
    return jdbc.query(COLUMNS + " WHERE subject_id = ?", ACCOUNT, subjectId).stream().findFirst();
  }

  /**
   * Creates a verified account (an invitation token proved the address). Empty when the e-mail
   * already exists: a concurrent acceptance won, and the caller must not reuse that account.
   */
  public Optional<UUID> insertVerified(String email, String displayName, String passwordHash) {
    return jdbc
        .query(
            """
            INSERT INTO user_accounts (email, display_name, password_hash, email_verified)
            VALUES (?, ?, ?, true)
            ON CONFLICT (email) DO NOTHING
            RETURNING subject_id
            """,
            (row, index) -> row.getObject("subject_id", UUID.class),
            email,
            displayName,
            passwordHash)
        .stream()
        .findFirst();
  }

  /**
   * Takes over an active but unverified account with the invitation token, which proves the
   * address. The new password replaces whatever was set before verification, and the auth_version
   * bump (V3 trigger) revokes any session opened with the old one.
   */
  public boolean claimUnverified(UUID subjectId, String displayName, String passwordHash) {
    // V10: the rule lives in the function; the API has no UPDATE on user_accounts.
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT claim_unverified_account(?, ?, ?)",
            Boolean.class,
            subjectId,
            displayName,
            passwordHash));
  }
}
