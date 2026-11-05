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
}
