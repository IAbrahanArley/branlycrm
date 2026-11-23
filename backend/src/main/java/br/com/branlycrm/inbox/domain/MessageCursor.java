package br.com.branlycrm.inbox.domain;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Position of a message in its conversation: the order it was stored in (CRM-083). Meta's own
 * timestamp is not used, because it ties within a second and a message may be stored late; the
 * arrival order never skips one in the poll. Opaque to the client (base64url).
 */
public record MessageCursor(long seq) {
  public MessageCursor {
    if (seq < 1) throw new IllegalArgumentException("seq");
  }

  public static MessageCursor of(InboxMessage message) {
    return new MessageCursor(message.seq());
  }

  public String encode() {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(("m" + seq).getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * @throws InvalidInputException with {@code field} for anything that is not a cursor
   */
  public static MessageCursor decode(String encoded, String field) {
    if (encoded == null || encoded.isEmpty() || encoded.length() > 40) {
      throw new InvalidInputException(field);
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII);
      if (!raw.matches("m[1-9][0-9]{0,17}")) throw new InvalidInputException(field);
      return new MessageCursor(Long.parseLong(raw.substring(1)));
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }
}
