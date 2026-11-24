package br.com.branlycrm.whatsapp.meta;

import br.com.branlycrm.whatsapp.meta.MetaGraphException.Kind;
import br.com.branlycrm.whatsapp.secrets.WebhookSecrets;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

/**
 * The few WhatsApp Cloud API calls of the channel connection (CRM-081b, ADR-036), with the JDK HTTP
 * client (no new dependency).
 *
 * <ul>
 *   <li>Every call with a business token carries {@code appsecret_proof}, so a leaked token is not
 *       enough alone ("Require App Secret").
 *   <li>Tokens and the app secret go in the body or the Authorization header whenever the Graph API
 *       allows it, and never into an exception, log or {@link #toString()}.
 *   <li>Failures become {@link MetaGraphException} with a stable code and a retry kind.
 * </ul>
 */
@Component
public class MetaGraphClient {
  private static final Pattern ID = Pattern.compile("[0-9]{1,32}");
  private static final Pattern TOKEN = Pattern.compile("[\\x21-\\x7E]{1,4096}");
  private static final Set<Integer> RATE_LIMITS = Set.of(4, 17, 32, 80007, 130429, 613);

  private final String appId;
  private final URI graph;
  private final String version;
  private final WebhookSecrets secrets;
  private final JsonMapper json;
  private final HttpClient http;
  private final Duration timeout;

  public MetaGraphClient(
      @Value("${app.whatsapp.meta.app-id:}") String appId,
      @Value("${app.whatsapp.meta.graph-url:https://graph.facebook.com}") String graphUrl,
      @Value("${app.whatsapp.meta.graph-version:v25.0}") String version,
      @Value("${app.whatsapp.meta.timeout-seconds:15}") long timeoutSeconds,
      WebhookSecrets secrets,
      ObjectProvider<JsonMapper> json) {
    if (!version.matches("v[0-9]{1,3}\\.[0-9]{1,3}")) {
      throw new IllegalArgumentException("Invalid Graph API version");
    }
    this.appId = appId == null ? "" : appId.strip();
    this.graph = URI.create(graphUrl.endsWith("/") ? graphUrl : graphUrl + "/");
    this.version = version;
    this.secrets = secrets;
    this.json = json.getIfAvailable(() -> JsonMapper.builder().build());
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** True when the app id and the mounted app secret are both present. */
  public boolean configured() {
    return ID.matcher(appId).matches() && secrets.appSecret().isPresent();
  }

  /**
   * Exchanges the Embedded Signup code (single use, about 30 seconds) for the business token.
   *
   * @throws MetaGraphException PERMANENT for a refused code
   */
  public String exchangeCode(String code) {
    if (code == null || !TOKEN.matcher(code).matches()) {
      throw new MetaGraphException(Kind.PERMANENT, "CODE_INVALID");
    }
    String secret = appSecret();
    JsonNode body =
        send(
            HttpRequest.newBuilder(uri("oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(Map.of("client_id", appId, "client_secret", secret, "code", code))),
            Kind.PERMANENT);
    String token = text(body.get("access_token"));
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new MetaGraphException(Kind.TRANSIENT, "TOKEN_MISSING");
    }
    return token;
  }

  /**
   * The WhatsApp Business Accounts the token was granted, read from the token itself ({@code
   * debug_token}); the ids from the browser are never trusted (ADR-036).
   *
   * @throws MetaGraphException TOKEN_INVALID when the token is invalid or belongs to another app
   */
  public List<String> grantedAccounts(String token) {
    String query = "input_token=" + encode(token);
    JsonNode data =
        send(
                HttpRequest.newBuilder(uri("debug_token?" + query))
                    .header("Authorization", "Bearer " + appId + "|" + appSecret())
                    .GET(),
                Kind.TOKEN_INVALID)
            .path("data");
    if (data.path("is_valid").getNodeType() != JsonNodeType.BOOLEAN
        || !data.path("is_valid").asBoolean()
        || !appId.equals(text(data.get("app_id")))) {
      throw new MetaGraphException(Kind.TOKEN_INVALID, "TOKEN_INVALID");
    }
    // Both scopes on the same account: managing it alone would connect a number that cannot send.
    Set<String> managed = targets(data, "whatsapp_business_management");
    managed.retainAll(targets(data, "whatsapp_business_messaging"));
    return List.copyOf(managed);
  }

  private static Set<String> targets(JsonNode data, String wanted) {
    Set<String> accounts = new LinkedHashSet<>();
    for (JsonNode scope : array(data.get("granular_scopes"))) {
      if (!wanted.equals(text(scope.get("scope")))) continue;
      for (JsonNode target : array(scope.get("target_ids"))) {
        String id = text(target);
        if (id != null && ID.matcher(id).matches()) accounts.add(id);
      }
    }
    return accounts;
  }

  /** The numbers of an account the token reaches. */
  public List<MetaPhoneNumber> phoneNumbers(String token, String wabaId) {
    requireId(wabaId);
    JsonNode body =
        send(
            authorized(
                    token,
                    uri(
                        wabaId
                            + "/phone_numbers?fields=id,display_phone_number&appsecret_proof="
                            + proof(token)))
                .GET(),
            Kind.PERMANENT);
    List<MetaPhoneNumber> numbers = new ArrayList<>();
    for (JsonNode number : array(body.get("data"))) {
      String id = text(number.get("id"));
      String display = text(number.get("display_phone_number"));
      if (id != null && ID.matcher(id).matches()) {
        numbers.add(new MetaPhoneNumber(id, display == null ? null : limited(display, 32)));
      }
    }
    return List.copyOf(numbers);
  }

  /** Registers the number for the Cloud API with the two-step PIN the worker generated. */
  public void register(String token, String phoneNumberId, String pin) {
    requireId(phoneNumberId);
    if (pin == null || !pin.matches("[0-9]{6}")) throw new IllegalArgumentException("pin");
    send(
        authorized(token, uri(phoneNumberId + "/register"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                form(
                    Map.of(
                        "messaging_product",
                        "whatsapp",
                        "pin",
                        pin,
                        "appsecret_proof",
                        proof(token)))),
        Kind.PERMANENT);
  }

  /** Subscribes this app to the account's webhooks. */
  public void subscribe(String token, String wabaId) {
    requireId(wabaId);
    send(
        authorized(token, uri(wabaId + "/subscribed_apps"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(form(Map.of("appsecret_proof", proof(token)))),
        Kind.PERMANENT);
  }

  /** Cancels the subscription; already cancelled counts as done. */
  public void unsubscribe(String token, String wabaId) {
    requireId(wabaId);
    send(
        authorized(token, uri(wabaId + "/subscribed_apps?appsecret_proof=" + proof(token)))
            .DELETE(),
        Kind.PERMANENT);
  }

  /**
   * Sends a text message (CRM-084a). {@code callbackData} (the outbox id) comes back in the
   * delivery statuses and reconciles an uncertain send. Never throws for a network failure: the
   * result says whether Meta may have received it (ADR-005).
   *
   * @param to the recipient's number in digits, without "+"
   */
  public MetaSendResult sendText(
      String token, String phoneNumberId, String to, String text, String callbackData) {
    requireId(phoneNumberId);
    if (to == null || !to.matches("[0-9]{6,20}")) throw new IllegalArgumentException("to");
    if (text == null || text.isEmpty() || text.length() > 4096) {
      throw new IllegalArgumentException("text");
    }
    var payload = json.createObjectNode();
    payload.put("messaging_product", "whatsapp");
    payload.put("recipient_type", "individual");
    payload.put("to", to);
    payload.put("type", "text");
    payload.putObject("text").put("body", text).put("preview_url", false);
    payload.put("biz_opaque_callback_data", callbackData);
    HttpRequest request;
    try {
      request =
          authorized(token, uri(phoneNumberId + "/messages?appsecret_proof=" + proof(token)))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .timeout(timeout)
              .build();
    } catch (MetaGraphException invalid) {
      return invalid.kind() == Kind.TOKEN_INVALID
          ? new MetaSendResult.TokenInvalid()
          : new MetaSendResult.NotSent(invalid.code());
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException notConnected) {
      return new MetaSendResult.NotSent("GRAPH_CONNECT");
    } catch (java.net.http.HttpTimeoutException timedOut) {
      return new MetaSendResult.Uncertain("GRAPH_TIMEOUT");
    } catch (IOException lost) {
      return new MetaSendResult.Uncertain("GRAPH_NETWORK");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new MetaSendResult.Uncertain("GRAPH_INTERRUPTED");
    }
    JsonNode body = parse(response.body());
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      JsonNode id = body.path("messages").path(0).path("id");
      return id.getNodeType() == JsonNodeType.STRING && !id.asText().isBlank()
          ? new MetaSendResult.Sent(id.asText())
          : new MetaSendResult.Uncertain("GRAPH_NO_ID");
    }
    JsonNode error = body.path("error");
    int code =
        error.path("code").getNodeType() == JsonNodeType.NUMBER ? error.path("code").asInt() : 0;
    if (code == 190) return new MetaSendResult.TokenInvalid();
    // Rate limits are refused before processing: nothing was sent.
    if (status == 429 || RATE_LIMITS.contains(code)) {
      return new MetaSendResult.NotSent(code == 0 ? "GRAPH_HTTP_429" : "META_" + code);
    }
    // A server error may come after Meta accepted the message.
    if (status >= 500) return new MetaSendResult.Uncertain("GRAPH_HTTP_" + status);
    return new MetaSendResult.Refused(code == 0 ? "GRAPH_HTTP_" + status : "META_" + code);
  }

  /**
   * Downloads a received media (CRM-084b1) into {@code target}: the Graph API gives a short-lived
   * URL, fetched with the token. Streaming, and stops past {@code maxBytes} whatever the declared
   * size says. Only an https URL, or one on the configured Graph host (tests), is followed.
   *
   * @return the bytes written
   * @throws MetaGraphException TRANSIENT, PERMANENT (unknown or expired media, too large) or
   *     TOKEN_INVALID
   */
  public long downloadMedia(
      String token, String mediaId, long maxBytes, java.nio.file.Path target) {
    if (mediaId == null || !mediaId.matches("[0-9A-Za-z_.-]{1,128}")) {
      throw new MetaGraphException(Kind.PERMANENT, "MEDIA_ID_INVALID");
    }
    JsonNode info =
        send(
            authorized(token, uri(mediaId + "?appsecret_proof=" + proof(token))).GET(),
            Kind.PERMANENT);
    String url = text(info.get("url"));
    URI location;
    try {
      location = url == null ? null : URI.create(url);
    } catch (IllegalArgumentException invalid) {
      location = null;
    }
    if (!mediaHostAllowed(location)) {
      throw new MetaGraphException(Kind.PERMANENT, "MEDIA_URL_INVALID");
    }
    HttpResponse<java.io.InputStream> response;
    try {
      response =
          http.send(
              authorized(token, location).GET().timeout(Duration.ofSeconds(120)).build(),
              HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException failure) {
      throw new MetaGraphException(Kind.TRANSIENT, "MEDIA_NETWORK");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MetaGraphException(Kind.TRANSIENT, "MEDIA_INTERRUPTED");
    }
    java.io.InputStream stream = response.body();
    // The request timeout covers the headers only: a stalled or trickling body is cut by closing
    // the stream at the deadline, so one download never holds the worker.
    java.util.concurrent.ScheduledFuture<?> deadline =
        WATCHDOG.schedule(
            () -> {
              try {
                stream.close();
              } catch (IOException ignored) {
                // Closing is the point.
              }
            },
            DOWNLOAD_DEADLINE.toSeconds(),
            java.util.concurrent.TimeUnit.SECONDS);
    try (java.io.InputStream body = stream;
        java.io.OutputStream out = java.nio.file.Files.newOutputStream(target)) {
      int status = response.statusCode();
      if (status == 401 || status == 403) {
        throw new MetaGraphException(Kind.TOKEN_INVALID, "MEDIA_HTTP_" + status);
      }
      if (status == 404 || status == 410) {
        throw new MetaGraphException(Kind.PERMANENT, "MEDIA_GONE");
      }
      if (status < 200 || status >= 300) {
        throw new MetaGraphException(Kind.TRANSIENT, "MEDIA_HTTP_" + status);
      }
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      int read;
      while ((read = body.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) throw new MetaGraphException(Kind.PERMANENT, "MEDIA_TOO_LARGE");
        out.write(buffer, 0, read);
      }
      return total;
    } catch (IOException failure) {
      throw new MetaGraphException(Kind.TRANSIENT, "MEDIA_NETWORK");
    } finally {
      deadline.cancel(false);
    }
  }

  /** Total time of one media download, headers and body. */
  static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(3);

  private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "whatsapp-media-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  /** Meta's media hosts; the token goes nowhere else. */
  private static final List<String> MEDIA_HOST_SUFFIXES =
      List.of(".fbsbx.com", ".whatsapp.net", ".facebook.com", ".fbcdn.net");

  /**
   * The media URL Meta gave: https on Meta's own hosts, default port, never an IP literal. The
   * configured Graph host itself is also accepted (it is where tests serve the files).
   */
  private boolean mediaHostAllowed(URI location) {
    if (location == null || location.getHost() == null) return false;
    String host = location.getHost().toLowerCase(java.util.Locale.ROOT);
    if (host.equals(graph.getHost())
        && java.util.Objects.equals(location.getScheme(), graph.getScheme())
        && location.getPort() == graph.getPort()) {
      return true;
    }
    if (!"https".equals(location.getScheme()) || location.getPort() != -1) return false;
    if (host.matches("[0-9.]+") || host.contains(":")) return false;
    return MEDIA_HOST_SUFFIXES.stream().anyMatch(host::endsWith);
  }

  private HttpRequest.Builder authorized(String token, URI uri) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new MetaGraphException(Kind.TOKEN_INVALID, "TOKEN_INVALID");
    }
    return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token);
  }

  /**
   * Sends and maps failures: rate limits, 5xx and network errors are transient; error 190 is an
   * invalid token; any other 4xx gets {@code refused}.
   */
  private JsonNode send(HttpRequest.Builder request, Kind refused) {
    HttpResponse<byte[]> response;
    try {
      response =
          http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException failure) {
      throw new MetaGraphException(Kind.TRANSIENT, "GRAPH_NETWORK");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MetaGraphException(Kind.TRANSIENT, "GRAPH_INTERRUPTED");
    }
    JsonNode body = parse(response.body());
    int status = response.statusCode();
    if (status >= 200 && status < 300) return body;
    JsonNode error = body.path("error");
    int code =
        error.path("code").getNodeType() == JsonNodeType.NUMBER ? error.path("code").asInt() : 0;
    if (code == 190) throw new MetaGraphException(Kind.TOKEN_INVALID, "GRAPH_190");
    if (status == 429 || status >= 500 || RATE_LIMITS.contains(code)) {
      throw new MetaGraphException(
          Kind.TRANSIENT, code == 0 ? "GRAPH_HTTP_" + status : "GRAPH_" + code);
    }
    throw new MetaGraphException(refused, code == 0 ? "GRAPH_HTTP_" + status : "GRAPH_" + code);
  }

  private JsonNode parse(byte[] body) {
    if (body == null || body.length == 0 || body.length > 1_048_576) {
      return json.createObjectNode();
    }
    try {
      JsonNode node = json.readTree(body);
      return node == null ? json.createObjectNode() : node;
    } catch (JacksonException malformed) {
      return json.createObjectNode();
    }
  }

  private String appSecret() {
    if (!ID.matcher(appId).matches())
      throw new MetaGraphException(Kind.NOT_CONFIGURED, "META_NOT_CONFIGURED");
    Optional<byte[]> secret = secrets.appSecret();
    if (secret.isEmpty()) throw new MetaGraphException(Kind.NOT_CONFIGURED, "META_NOT_CONFIGURED");
    byte[] bytes = secret.get();
    try {
      return new String(bytes, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  /** {@code appsecret_proof}: HMAC-SHA256 of the token with the app secret, in hex. */
  String proof(String token) {
    byte[] secret =
        secrets
            .appSecret()
            .orElseThrow(() -> new MetaGraphException(Kind.NOT_CONFIGURED, "META_NOT_CONFIGURED"));
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException missing) {
      throw new IllegalStateException("HmacSHA256 unavailable", missing);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private URI uri(String path) {
    return graph.resolve(version + "/" + path);
  }

  private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
    return HttpRequest.BodyPublishers.ofString(
        fields.entrySet().stream()
            .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
            .collect(Collectors.joining("&")));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("id");
  }

  private static String limited(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }

  private static String text(JsonNode node) {
    return node != null && node.getNodeType() == JsonNodeType.STRING ? node.asText() : null;
  }

  private static List<JsonNode> array(JsonNode node) {
    if (node == null || node.getNodeType() != JsonNodeType.ARRAY) return List.of();
    List<JsonNode> items = new ArrayList<>();
    node.forEach(items::add);
    return items;
  }

  @Override
  public String toString() {
    return "MetaGraphClient[" + version + "]";
  }
}
