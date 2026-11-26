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
    var payload = message(to, "text", callbackData);
    payload.putObject("text").put("body", text).put("preview_url", false);
    return sendMessage(token, phoneNumberId, payload);
  }

  /**
   * Sends a file already uploaded to Meta (CRM-084b2), with the guarantees of {@link #sendText}.
   *
   * @param type image, video, audio or document
   * @param caption optional; never for audio
   * @param fileName optional, documents only
   */
  public MetaSendResult sendMedia(
      String token,
      String phoneNumberId,
      String to,
      String type,
      String mediaId,
      String caption,
      String fileName,
      String callbackData) {
    requireId(phoneNumberId);
    if (to == null || !to.matches("[0-9]{6,20}")) throw new IllegalArgumentException("to");
    if (!List.of("image", "video", "audio", "document").contains(type)) {
      throw new IllegalArgumentException("type");
    }
    if (mediaId == null || !mediaId.matches("[0-9A-Za-z_.-]{1,128}")) {
      throw new IllegalArgumentException("mediaId");
    }
    var payload = message(to, type, callbackData);
    var media = payload.putObject(type).put("id", mediaId);
    if (caption != null && !type.equals("audio")) media.put("caption", caption);
    if (fileName != null && type.equals("document")) media.put("filename", fileName);
    return sendMessage(token, phoneNumberId, payload);
  }

  /**
   * Sends an approved template (CRM-085), with the guarantees of {@link #sendText}. The values go
   * in the order of the template's keys; named templates also carry each key.
   */
  public MetaSendResult sendTemplate(
      String token,
      String phoneNumberId,
      String to,
      br.com.branlycrm.whatsapp.domain.MessageTemplate template,
      Map<String, String> header,
      Map<String, String> body,
      String callbackData) {
    requireId(phoneNumberId);
    if (to == null || !to.matches("[0-9]{6,20}")) throw new IllegalArgumentException("to");
    var payload = message(to, "template", callbackData);
    var content = payload.putObject("template");
    content.put("name", template.name());
    content.putObject("language").put("code", template.language());
    var components = content.putArray("components");
    parameters(components, "header", template.headerParams(), header, template.named());
    parameters(components, "body", template.bodyParams(), body, template.named());
    return sendMessage(token, phoneNumberId, payload);
  }

  private static void parameters(
      tools.jackson.databind.node.ArrayNode components,
      String type,
      List<String> keys,
      Map<String, String> values,
      boolean named) {
    if (keys.isEmpty()) return;
    var component = components.addObject().put("type", type);
    var parameters = component.putArray("parameters");
    for (String key : keys) {
      String value = values == null ? null : values.get(key);
      if (value == null) throw new IllegalArgumentException(type + "." + key);
      var parameter = parameters.addObject().put("type", "text");
      if (named) parameter.put("parameter_name", key);
      parameter.put("text", value);
    }
  }

  /**
   * The message templates of an account (CRM-085), following Meta's pages up to {@link
   * #TEMPLATE_PAGES} pages of 100. Definitions Meta returns malformed are left out.
   *
   * @throws MetaGraphException TRANSIENT, PERMANENT or TOKEN_INVALID
   */
  public TemplateList templates(String token, String wabaId) {
    requireId(wabaId);
    List<br.com.branlycrm.whatsapp.domain.MessageTemplate> templates = new ArrayList<>();
    List<String> unreadable = new ArrayList<>();
    String after = null;
    for (int page = 0; page < TEMPLATE_PAGES; page++) {
      String query =
          wabaId
              + "/message_templates?fields=id,name,language,status,category,parameter_format,"
              + "components&limit=100&appsecret_proof="
              + proof(token)
              + (after == null ? "" : "&after=" + encode(after));
      JsonNode body = send(authorized(token, uri(query)).GET(), Kind.PERMANENT);
      for (JsonNode item : array(body.get("data"))) {
        List<br.com.branlycrm.whatsapp.domain.MessageTemplate.Component> components =
            new ArrayList<>();
        for (JsonNode component : array(item.get("components"))) {
          List<br.com.branlycrm.whatsapp.domain.MessageTemplate.Button> buttons = new ArrayList<>();
          for (JsonNode button : array(component.get("buttons"))) {
            buttons.add(
                new br.com.branlycrm.whatsapp.domain.MessageTemplate.Button(
                    text(button.get("type")), text(button.get("url"))));
          }
          components.add(
              new br.com.branlycrm.whatsapp.domain.MessageTemplate.Component(
                  text(component.get("type")),
                  text(component.get("format")),
                  text(component.get("text")),
                  buttons));
        }
        String id = text(item.get("id"));
        var template =
            br.com.branlycrm.whatsapp.domain.MessageTemplate.of(
                id,
                text(item.get("name")),
                text(item.get("language")),
                text(item.get("category")),
                text(item.get("status")),
                text(item.get("parameter_format")),
                components);
        if (template.isPresent()) templates.add(template.get());
        else if (id != null && ID.matcher(id).matches()) unreadable.add(id);
      }
      if (body.path("paging").get("next") == null) {
        return new TemplateList(List.copyOf(templates), List.copyOf(unreadable));
      }
      after = text(body.path("paging").path("cursors").get("after"));
      if (after == null || !after.matches("[0-9A-Za-z=_-]{1,512}")) {
        // A list cut short would mark the rest as deleted.
        throw new MetaGraphException(Kind.TRANSIENT, "TEMPLATES_PAGING");
      }
    }
    throw new MetaGraphException(Kind.TRANSIENT, "TEMPLATES_TOO_MANY");
  }

  /**
   * The account's templates, complete: the ones read, and the ids of definitions Meta returned
   * malformed (kept as they were, never marked deleted).
   */
  public record TemplateList(
      List<br.com.branlycrm.whatsapp.domain.MessageTemplate> templates, List<String> unreadable) {}

  /** At most 1000 templates per account (Meta allows up to 250 per account by default). */
  static final int TEMPLATE_PAGES = 10;

  private tools.jackson.databind.node.ObjectNode message(
      String to, String type, String callbackData) {
    var payload = json.createObjectNode();
    payload.put("messaging_product", "whatsapp");
    payload.put("recipient_type", "individual");
    payload.put("to", to);
    payload.put("type", type);
    payload.put("biz_opaque_callback_data", callbackData);
    return payload;
  }

  /** Posts a message; the result says whether Meta may have received it (ADR-005). */
  private MetaSendResult sendMessage(
      String token, String phoneNumberId, tools.jackson.databind.node.ObjectNode payload) {
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
   * Uploads a file to Meta for sending (CRM-084b2) and returns Meta's media id as {@link
   * MetaSendResult.Sent}. An upload is not a message: when its answer is lost it is simply done
   * again, so failures are NotSent (retry), Refused or TokenInvalid, never Uncertain.
   */
  public MetaSendResult uploadMedia(
      String token,
      String phoneNumberId,
      java.nio.file.Path file,
      String contentType,
      String fileName) {
    requireId(phoneNumberId);
    if (contentType == null || !contentType.matches("[a-z]+/[0-9A-Za-z.+-]{1,90}")) {
      throw new IllegalArgumentException("contentType");
    }
    String name = fileName == null ? "file" : fileName.replaceAll("[\\p{Cntrl}\"\\\\/]", "_");
    String boundary = "branly-" + java.util.UUID.randomUUID();
    String head =
        part(boundary, "messaging_product", null, null)
            + "whatsapp\r\n"
            + part(boundary, "type", null, null)
            + contentType
            + "\r\n"
            + part(boundary, "file", name, contentType);
    String tail = "\r\n--" + boundary + "--\r\n";
    HttpRequest request;
    try {
      request =
          authorized(token, uri(phoneNumberId + "/media?appsecret_proof=" + proof(token)))
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(
                  HttpRequest.BodyPublishers.concat(
                      HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
                      HttpRequest.BodyPublishers.ofFile(file),
                      HttpRequest.BodyPublishers.ofString(tail)))
              .timeout(UPLOAD_TIMEOUT)
              .build();
    } catch (MetaGraphException invalid) {
      return invalid.kind() == Kind.TOKEN_INVALID
          ? new MetaSendResult.TokenInvalid()
          : new MetaSendResult.NotSent(invalid.code());
    } catch (java.io.FileNotFoundException missing) {
      return new MetaSendResult.NotSent("MEDIA_FILE_MISSING");
    }
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException lost) {
      return new MetaSendResult.NotSent("UPLOAD_NETWORK");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new MetaSendResult.NotSent("UPLOAD_INTERRUPTED");
    }
    JsonNode body = parse(response.body());
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      String id = text(body.get("id"));
      return id != null && id.matches("[0-9A-Za-z_.-]{1,128}")
          ? new MetaSendResult.Sent(id)
          : new MetaSendResult.NotSent("UPLOAD_NO_ID");
    }
    JsonNode error = body.path("error");
    int code =
        error.path("code").getNodeType() == JsonNodeType.NUMBER ? error.path("code").asInt() : 0;
    if (code == 190) return new MetaSendResult.TokenInvalid();
    if (status == 429 || status >= 500 || RATE_LIMITS.contains(code)) {
      return new MetaSendResult.NotSent(code == 0 ? "UPLOAD_HTTP_" + status : "META_" + code);
    }
    return new MetaSendResult.Refused(code == 0 ? "UPLOAD_HTTP_" + status : "META_" + code);
  }

  /** The header of one multipart field, ending with the blank line before its value. */
  private static String part(String boundary, String field, String fileName, String type) {
    StringBuilder header =
        new StringBuilder("--")
            .append(boundary)
            .append("\r\nContent-Disposition: form-data; name=\"")
            .append(field)
            .append('"');
    if (fileName != null) header.append("; filename=\"").append(fileName).append('"');
    if (type != null) header.append("\r\nContent-Type: ").append(type);
    return header.append("\r\n\r\n").toString();
  }

  /** A 16 MB upload on a slow link. */
  public static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(2);

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
