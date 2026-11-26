package br.com.branlycrm.whatsapp.repositories;

import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What the worker needs to send from a channel (CRM-084a): its number and its sealed token. Worker
 * only: the API role cannot read the sealed columns, and the ArchUnit keeps this class in the
 * worker.
 */
@Repository
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class OutboundChannelRepository {
  private final JdbcTemplate jdbc;

  public OutboundChannelRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** An active channel's sending data; the token stays sealed here. */
  public record Sender(String phoneNumberId, SealedSecret token, String wabaId) {
    @Override
    public String toString() {
      return "Sender[redacted]";
    }
  }

  public Optional<Sender> sender(UUID channelId) {
    return jdbc
        .query(
            "SELECT phone_number_id, sealed_token, key_version, waba_id FROM whatsapp_channels"
                + " WHERE id = ? AND status = 'ACTIVE' AND sealed_token IS NOT NULL",
            (row, index) ->
                new Sender(
                    row.getString("phone_number_id"),
                    new SealedSecret(row.getBytes("sealed_token"), row.getInt("key_version")),
                    row.getString("waba_id")),
            channelId)
        .stream()
        .findFirst();
  }
}
