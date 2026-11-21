package br.com.branlycrm.whatsapp;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WHATSAPP;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.repositories.ChannelOnboardingRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.worker.ChannelOnboardingProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Connection of the clinic's WhatsApp number (CRM-081b, ADR-036) over real HTTP, against a fake
 * Graph API: the code is exchanged and sealed by the API, the worker (with its own role) confirms
 * the number from the token, registers it and subscribes the webhooks. Roles, clinics and agencies
 * are isolated, and no token or secret reaches a response or the log. Synthetic data only.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S",
      "app.whatsapp.meta.app-id=1234",
      MfaFixture.KEY_PROPERTY
    })
class WhatsappConnectionIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory("branly-connection-it-");
  private static final Path FILES = temporaryDirectory("branly-connection-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String LOGIN_PASSWORD = "Synthetic connection secret 2026!";
  private static final String CHANNEL = "/v1/clinic/whatsapp/channel";
  private static final FakeGraph GRAPH = new FakeGraph();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired ChannelSealer sealer;
  @Autowired MetaGraphClient meta;
  @Autowired ApplicationContext context;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    Files.writeString(FILES.resolve("app-secret"), APP_SECRET);
    Files.createDirectories(FILES.resolve("keys/public"));
    Files.createDirectories(FILES.resolve("keys/private"));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    KeyPair pair = generator.generateKeyPair();
    Files.writeString(
        FILES.resolve("keys/public/channel-key-v1.pub.pem"),
        pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    Files.writeString(
        FILES.resolve("keys/private/channel-key-v1.pem"),
        pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    GRAPH.start();
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", POSTGRES::getDatabaseName);
    registry.add(
        "app.whatsapp.webhook.app-secret-file", () -> FILES.resolve("app-secret").toString());
    registry.add(
        "app.whatsapp.keys.public-directory", () -> FILES.resolve("keys/public").toString());
    registry.add("app.whatsapp.meta.graph-url", GRAPH::url);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @AfterAll
  static void cleanUp() throws IOException {
    GRAPH.stop();
    for (Path directory : new Path[] {SECRETS, FILES}) {
      try (Stream<Path> files = Files.walk(directory)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }

  @Test
  void connectsConfirmsRegistersAndDisconnects(CapturedOutput output) throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA, null);
    UUID clinicB = provision(account("owner-b@example.test"), null);
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);
    ChannelOnboardingProcessor worker = worker();

    assertThat(json(owner.get(CHANNEL))).contains("\"status\":\"NONE\"");
    assertThat(attendant.get(CHANNEL).statusCode()).isEqualTo(403);
    assertThat(connect(attendant, "code-a").statusCode()).isEqualTo(403);

    // A refused code creates nothing.
    var refused = connect(owner, "code-bad");
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(refused.body()).contains("CHANNEL_CODE_INVALID");
    assertThat(count("whatsapp_channels")).isZero();

    // Exchange and seal in the API; the worker confirms, registers and subscribes.
    var connecting = connect(owner, "code-a");
    assertThat(connecting.statusCode()).isEqualTo(202);
    assertThat(connecting.body()).contains("\"status\":\"CONNECTING\"").doesNotContain("TOKEN-A");
    byte[] sealed =
        jdbc.queryForObject(
            "SELECT sealed_token FROM whatsapp_channels WHERE tenant_id = ?",
            byte[].class,
            clinicA);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("TOKEN-A");
    assertThat(worker.runPending(10)).isEqualTo(3);
    var active = owner.get(CHANNEL);
    assertThat(active.body())
        .contains("\"status\":\"ACTIVE\"")
        .contains("+55 83 90000-0001")
        .doesNotContain("6001")
        .doesNotContain("5001");
    assertThat(
            jdbc.queryForObject(
                "SELECT waba_id || ':' || phone_number_id FROM whatsapp_channels"
                    + " WHERE tenant_id = ?",
                String.class,
                clinicA))
        .isEqualTo("5001:6001");
    assertThat(GRAPH.calls)
        .contains("POST /v25.0/6001/register", "POST /v25.0/5001/subscribed_apps");
    assertThat(GRAPH.proofsValid).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT sealed_pin IS NOT NULL FROM whatsapp_channels WHERE tenant_id = ?",
                Boolean.class,
                clinicA))
        .isTrue();
    assertThat(connect(owner, "code-a").body()).contains("CHANNEL_ALREADY_CONNECTED");

    // Clinic B: the same number fails; two numbers need a choice; coexistence skips registration.
    connect(ownerB, "code-same");
    worker.runPending(10);
    assertThat(ownerB.get(CHANNEL).body())
        .contains("\"status\":\"FAILED\"")
        .contains("NUMBER_IN_USE");
    assertThat(post(ownerB, CHANNEL + "/disconnect").statusCode()).isEqualTo(202);
    worker.runPending(10);
    assertThat(ownerB.get(CHANNEL).body()).contains("\"status\":\"NONE\"");

    connect(ownerB, "code-two");
    worker.runPending(10);
    assertThat(ownerB.get(CHANNEL).body()).contains("AMBIGUOUS_NUMBER");
    post(ownerB, CHANNEL + "/disconnect");
    worker.runPending(10);
    var chosen =
        ownerB.postJson(
            CHANNEL + "/connect",
            "{\"code\":\"code-two\",\"phoneNumberId\":\"6003\",\"coexistence\":true}",
            true);
    assertThat(chosen.statusCode()).isEqualTo(202);
    worker.runPending(10);
    assertThat(ownerB.get(CHANNEL).body()).contains("\"status\":\"ACTIVE\"").contains("0003");
    assertThat(GRAPH.calls).doesNotContain("POST /v25.0/6003/register");
    // A's channel is untouched by everything B did.
    assertThat(owner.get(CHANNEL).body()).contains("+55 83 90000-0001");

    // A revoked token fails the channel.
    UUID clinicC = provision(account("owner-c@example.test"), null);
    Browser ownerC = selected("owner-c@example.test", clinicC);
    connect(ownerC, "code-revoked");
    worker.runPending(10);
    assertThat(ownerC.get(CHANNEL).body()).contains("TOKEN_INVALID");

    // Agency: only clinics of its own portfolio, as if the others did not exist.
    UUID agency =
        jdbc.queryForObject(
            "INSERT INTO agencies (name) VALUES ('Agência sintética') RETURNING agency_id",
            UUID.class);
    UUID admin = account("agency@example.test");
    jdbc.update(
        "INSERT INTO agency_memberships (agency_id, subject_id, role) VALUES (?, ?, 'AGENCY_ADMIN')",
        agency,
        admin);
    UUID clinicD = provision(account("owner-d@example.test"), agency);
    Browser agencyAdmin = login("agency@example.test");
    String agencyChannel = "/v1/agencies/" + agency + "/clinics/" + clinicD + "/whatsapp/channel";
    assertThat(agencyAdmin.get(agencyChannel).body()).contains("\"status\":\"NONE\"");
    var foreign =
        agencyAdmin.get("/v1/agencies/" + agency + "/clinics/" + clinicA + "/whatsapp/channel");
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(foreign.body()).contains("CLINIC_NOT_FOUND");
    assertThat(
            agencyAdmin
                .get(
                    "/v1/agencies/"
                        + UUID.randomUUID()
                        + "/clinics/"
                        + clinicD
                        + "/whatsapp/channel")
                .statusCode())
        .isEqualTo(404);
    assertThat(owner.get(agencyChannel).statusCode()).isEqualTo(404);

    // The agency connects the other number of B's account (one account, two clinics). When it
    // disconnects, the account keeps its webhooks, because B still uses them.
    var agencyConnect =
        agencyAdmin.postJson(
            agencyChannel + "/connect", "{\"code\":\"code-two\",\"phoneNumberId\":\"6002\"}", true);
    assertThat(agencyConnect.statusCode()).as(agencyConnect.body()).isEqualTo(202);
    worker.runPending(10);
    assertThat(agencyAdmin.get(agencyChannel).body()).contains("\"status\":\"ACTIVE\"");
    assertThat(agencyAdmin.postJson(agencyChannel + "/disconnect", "{}", true).statusCode())
        .isEqualTo(202);
    worker.runPending(10);
    assertThat(agencyAdmin.get(agencyChannel).body()).contains("\"status\":\"NONE\"");
    assertThat(GRAPH.calls).doesNotContain("DELETE /v25.0/5002/subscribed_apps");
    assertThat(ownerB.get(CHANNEL).body()).contains("\"status\":\"ACTIVE\"");

    // Disconnection: webhooks unsubscribed, secrets wiped.
    var disconnecting = post(owner, CHANNEL + "/disconnect");
    assertThat(disconnecting.body()).contains("\"status\":\"DISCONNECTING\"");
    assertThat(post(owner, CHANNEL + "/disconnect").body()).contains("CHANNEL_BUSY");
    worker.runPending(10);
    assertThat(GRAPH.calls).contains("DELETE /v25.0/5001/subscribed_apps");
    assertThat(owner.get(CHANNEL).body()).contains("\"status\":\"NONE\"");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM whatsapp_channels WHERE tenant_id = ?"
                    + " AND status = 'DISCONNECTED' AND sealed_token IS NULL"
                    + " AND sealed_pin IS NULL",
                Integer.class,
                clinicA))
        .isEqualTo(1);
    assertThat(post(owner, CHANNEL + "/disconnect").body()).contains("CHANNEL_NOT_CONNECTED");

    assertThat(output.getAll())
        .doesNotContain("TOKEN-A")
        .doesNotContain("TOKEN-B")
        .doesNotContain(APP_SECRET)
        .doesNotContain("code-a");
  }

  private ChannelOnboardingProcessor worker() {
    DriverManagerDataSource role =
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()), WHATSAPP, PASSWORD);
    return new ChannelOnboardingProcessor(
        new ChannelOnboardingRepository(new JdbcTemplate(role)),
        new ChannelUnsealer(FILES.resolve("keys/private").toString()),
        sealer,
        meta,
        context.getBeanProvider(Clock.class));
  }

  private static HttpResponse<String> connect(Browser browser, String code) throws Exception {
    return browser.postJson(CHANNEL + "/connect", "{\"code\":\"" + code + "\"}", true);
  }

  private static HttpResponse<String> post(Browser browser, String path) throws Exception {
    return browser.postJson(path, "{}", true);
  }

  private static String json(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private Browser login(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, jdbc);
    return browser;
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = login(email);
    var selection = browser.put("/v1/auth/clinic/" + clinic, true);
    assertThat(selection.statusCode()).as(selection.body()).isEqualTo(200);
    return browser;
  }

  private UUID account(String email) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', ?, true)",
        id,
        email,
        encoder.encode(LOGIN_PASSWORD));
    return id;
  }

  private UUID provision(UUID owner, UUID agency) {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, agency_id) VALUES (?, 'Clínica sintética', ?, ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        agency);
    jdbc.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return tenant;
  }

  private void member(UUID tenant, UUID subject, String role) {
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role) VALUES (?, ?, ?)",
        tenant,
        subject,
        role);
  }

  private static String pem(String label, byte[] der) {
    return "-----BEGIN "
        + label
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END "
        + label
        + "-----\n";
  }

  private static Path temporaryDirectory(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** A Graph API with three signup codes, two accounts and a revoked token. */
  static final class FakeGraph {
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean proofsValid = true;
    private HttpServer server;

    void start() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
    }

    void stop() {
      if (server != null) server.stop(0);
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getRawQuery();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      calls.add(method + " " + path);
      String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
      String proof = param(query, "appsecret_proof");
      if (proof == null) proof = param(body, "appsecret_proof");
      if (token != null && token.startsWith("TOKEN-") && !proof(token).equals(proof)) {
        proofsValid = false;
      }
      switch (method + " " + path) {
        case "POST /v25.0/oauth/access_token" -> {
          String code = param(body, "code");
          boolean app =
              "1234".equals(param(body, "client_id"))
                  && APP_SECRET.equals(param(body, "client_secret"));
          String issued =
              !app
                  ? null
                  : switch (code == null ? "" : code) {
                    case "code-a", "code-same" -> "TOKEN-A";
                    case "code-two" -> "TOKEN-B";
                    case "code-revoked" -> "TOKEN-REVOKED";
                    default -> null;
                  };
          if (issued == null) reply(exchange, 400, "{\"error\":{\"code\":100}}");
          else reply(exchange, 200, "{\"access_token\":\"" + issued + "\"}");
        }
        case "GET /v25.0/debug_token" -> {
          String input = param(query, "input_token");
          if (!("1234|" + APP_SECRET).equals(token) || "TOKEN-REVOKED".equals(input)) {
            reply(exchange, 400, "{\"error\":{\"code\":190}}");
          } else {
            String waba = "TOKEN-B".equals(input) ? "5002" : "5001";
            reply(
                exchange,
                200,
                "{\"data\":{\"is_valid\":true,\"app_id\":\"1234\",\"granular_scopes\":["
                    + "{\"scope\":\"whatsapp_business_management\",\"target_ids\":[\""
                    + waba
                    + "\"]},{\"scope\":\"whatsapp_business_messaging\",\"target_ids\":[\""
                    + waba
                    + "\"]}]}}");
          }
        }
        case "GET /v25.0/5001/phone_numbers" ->
            reply(
                exchange,
                200,
                "{\"data\":[{\"id\":\"6001\",\"display_phone_number\":\"+55 83 90000-0001\"}]}");
        case "GET /v25.0/5002/phone_numbers" ->
            reply(
                exchange,
                200,
                "{\"data\":[{\"id\":\"6002\",\"display_phone_number\":\"+55 83 90000-0002\"},"
                    + "{\"id\":\"6003\",\"display_phone_number\":\"+55 83 90000-0003\"}]}");
        default -> {
          if (path.endsWith("/register") || path.endsWith("/subscribed_apps")) {
            reply(exchange, 200, "{\"success\":true}");
          } else {
            reply(exchange, 404, "{\"error\":{\"code\":803}}");
          }
        }
      }
    }

    private static String proof(String token) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private static String param(String encoded, String name) {
      if (encoded == null) return null;
      for (String pair : encoded.split("&")) {
        int at = pair.indexOf('=');
        if (at > 0 && pair.substring(0, at).equals(name)) {
          return java.net.URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8);
        }
      }
      return null;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
