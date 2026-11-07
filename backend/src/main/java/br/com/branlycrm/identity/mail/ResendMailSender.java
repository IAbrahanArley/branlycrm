package br.com.branlycrm.identity.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resend HTTP API (ADR-021), with the JDK client and no SDK. The API key exists only in the API
 * process and is never logged; failures log the HTTP status only.
 */
public final class ResendMailSender implements MailSender {
  static final URI ENDPOINT = URI.create("https://api.resend.com/emails");
  private static final Logger log = LoggerFactory.getLogger(ResendMailSender.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient client;
  private final URI endpoint;
  private final String apiKey;
  private final String from;

  public ResendMailSender(HttpClient client, URI endpoint, String apiKey, String from) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("RESEND_API_KEY is required with MAIL_PROVIDER=resend");
    }
    this.client = client;
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.from = from;
  }

  @Override
  public boolean send(MailMessage message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", from);
    body.put("to", List.of(message.to()));
    body.put("subject", message.subject());
    body.put("text", message.text());
    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
              .build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 == 2) return true;
      log.warn("Resend refused the message: MAIL_PROVIDER_STATUS_{}", response.statusCode());
      return false;
    } catch (IOException | RuntimeException failure) {
      // After the commit: a failure here is a FAILED delivery, never a 500 for a saved invite.
      log.warn("Resend unreachable: MAIL_PROVIDER_UNAVAILABLE");
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
