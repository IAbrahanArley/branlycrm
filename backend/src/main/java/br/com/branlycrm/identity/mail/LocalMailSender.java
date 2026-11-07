package br.com.branlycrm.identity.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development and test adapter: writes each message to a file instead of sending it, so no real
 * person ever receives mail from a local environment. Refused with an HTTPS origin.
 */
public final class LocalMailSender implements MailSender {
  private static final Logger log = LoggerFactory.getLogger(LocalMailSender.class);

  private final Path directory;

  public LocalMailSender(Path directory) {
    this.directory = directory;
  }

  @Override
  public boolean send(MailMessage message) {
    try {
      Files.createDirectories(directory);
      Path file =
          directory.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".txt");
      String content =
          "To: " + message.to() + "\nSubject: " + message.subject() + "\n\n" + message.text();
      Files.writeString(file, content, StandardCharsets.UTF_8);
      return true;
    } catch (IOException failure) {
      log.warn("Local mail could not be written: MAIL_LOCAL_WRITE_FAILED");
      return false;
    }
  }
}
