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
