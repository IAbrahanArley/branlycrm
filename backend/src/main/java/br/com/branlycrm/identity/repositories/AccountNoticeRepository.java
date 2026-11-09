package br.com.branlycrm.identity.repositories;

import br.com.branlycrm.identity.domain.AccountNotice;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Account notices of the control database (V14). Written only by platform functions; the runtime
 * reads them and claims or releases them only through account_notice_claim and _release.
 */
@Repository
public class AccountNoticeRepository {
  private final JdbcTemplate jdbc;

  public AccountNoticeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<AccountNotice> pending(UUID subject) {
    return jdbc.query(
        "SELECT notice_id, kind FROM account_notices WHERE subject_id = ? AND sent_at IS NULL"
            + " ORDER BY created_at, notice_id LIMIT 10",
        (row, index) ->
            new AccountNotice(row.getObject("notice_id", UUID.class), row.getString("kind")),
        subject);
  }

  /** True for exactly one caller: the one that sends it. */
  public boolean claim(UUID notice, UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT account_notice_claim(?, ?)", Boolean.class, notice, subject));
  }

  public void release(UUID notice, UUID subject) {
    jdbc.queryForObject("SELECT account_notice_release(?, ?)", Boolean.class, notice, subject);
  }
}
