package br.com.branlycrm.api.identity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcTemplate database;
    public UserAccountRepository(JdbcTemplate database) { this.database = database; }
    public Optional<UserAccount> findByEmail(String email) {
        return query("email = ?", email);
    }
    public Optional<UserAccount> findById(UUID subjectId) {
        return query("subject_id = ?", subjectId);
    }
    private Optional<UserAccount> query(String condition, Object parameter) {
        return database.query("SELECT subject_id, email, display_name, password_hash, active, email_verified, auth_version FROM user_accounts WHERE " + condition,
            (rs, row) -> new UserAccount(rs.getObject("subject_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("active"),
                rs.getBoolean("email_verified"), rs.getLong("auth_version")), parameter).stream().findFirst();
    }
}