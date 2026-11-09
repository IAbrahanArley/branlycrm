package br.com.branlycrm.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP client with its own cookie jar, mirroring how the Next.js frontend talks to the API.
 */
public final class Browser {
  public static final JsonMapper JSON = JsonMapper.builder().build();

  private final String origin;
  private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  private final HttpClient client;
  private String csrfHeader;
  private String csrfToken;

  public Browser(int port) {
    this.origin = "http://localhost:" + port;
    this.client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  public HttpResponse<String> csrf() throws Exception {
    HttpResponse<String> response = get("/v1/auth/csrf");
    JsonNode body = JSON.readTree(response.body());
    csrfHeader = body.get("headerName").asText();
    csrfToken = body.get("token").asText();
    return response;
  }

  public HttpResponse<String> login(String email, String password) throws Exception {
    return form(
        "/v1/auth/login",
        "email="
            + URLEncoder.encode(email, StandardCharsets.UTF_8)
            + "&password="
            + URLEncoder.encode(password, StandardCharsets.UTF_8),
        true);
  }

  public HttpResponse<String> loginWithoutCsrf(String email, String password) throws Exception {
    return form("/v1/auth/login", "email=" + email + "&password=" + password, false);
  }

  public HttpResponse<String> get(String path) throws Exception {
    return send(request(path).GET());
  }

  public HttpResponse<String> put(String path, boolean withCsrf) throws Exception {
    return send(csrf(request(path), withCsrf).PUT(HttpRequest.BodyPublishers.noBody()));
  }

  public HttpResponse<String> post(String path, boolean withCsrf) throws Exception {
    return send(csrf(request(path), withCsrf).POST(HttpRequest.BodyPublishers.noBody()));
  }

  public HttpResponse<String> putJson(String path, String json, boolean withCsrf) throws Exception {
    return send(
        csrf(request(path), withCsrf)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)));
  }

  public HttpResponse<String> postJson(String path, String json, boolean withCsrf)
      throws Exception {
    return send(
        csrf(request(path), withCsrf)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
  }

  public HttpResponse<String> delete(String path, boolean withCsrf) throws Exception {
    return send(csrf(request(path), withCsrf).DELETE());
  }

  public HttpResponse<String> deleteJson(String path, String json, boolean withCsrf)
      throws Exception {
    return send(
        csrf(request(path), withCsrf)
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(json)));
  }

  /** Any method, with CSRF and a JSON body: for sweeping every mapped route. */
  public HttpResponse<String> send(String method, String path, String json) throws Exception {
    return send(
        csrf(request(path), true)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json)));
  }

  /** Current value of a cookie of this browser, or null. */
  public String cookie(String name) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals(name))
        .map(java.net.HttpCookie::getValue)
        .findFirst()
        .orElse(null);
  }

  private HttpResponse<String> form(String path, String body, boolean withCsrf) throws Exception {
    return send(
        csrf(request(path), withCsrf)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
  }

  private HttpRequest.Builder csrf(HttpRequest.Builder builder, boolean withCsrf) {
    if (withCsrf) {
      if (csrfToken == null) throw new IllegalStateException("Call csrf() first");
      builder.header(csrfHeader, csrfToken);
    }
    return builder;
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
