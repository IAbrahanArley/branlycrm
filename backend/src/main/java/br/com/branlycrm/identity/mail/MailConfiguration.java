package br.com.branlycrm.identity.mail;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the mail adapter (ADR-021). The local adapter writes links with live tokens to disk, so
 * it refuses to start with an HTTPS origin, that is, a real deployment.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class MailConfiguration {
  @Bean
  PublicOrigin publicOrigin(@Value("${app.public-origin:http://localhost:8088}") String origin) {
    return PublicOrigin.of(origin);
  }

  @Bean
  MailSender mailSender(
      @Value("${app.mail.provider:local}") String provider,
      @Value("${app.mail.local-directory:${java.io.tmpdir}/branly-mail}") String directory,
      @Value("${app.mail.resend-api-key:}") String apiKey,
      @Value("${app.mail.from-address:no-reply@example.test}") String fromAddress,
      @Value("${app.mail.from-name:Branly CRM}") String fromName,
      PublicOrigin origin) {
    return switch (provider) {
      case "local" -> {
        if (origin.secure()) {
          throw new IllegalStateException("MAIL_PROVIDER=local is refused with an HTTPS origin");
        }
        yield new LocalMailSender(Path.of(directory));
      }
      case "resend" ->
          new ResendMailSender(
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
              ResendMailSender.ENDPOINT,
              apiKey,
              fromName + " <" + fromAddress + ">");
      default -> throw new IllegalStateException("MAIL_PROVIDER must be local or resend");
    };
  }
}
