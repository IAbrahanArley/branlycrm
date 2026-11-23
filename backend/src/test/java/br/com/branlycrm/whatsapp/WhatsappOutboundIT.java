package br.com.branlycrm.whatsapp;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WHATSAPP;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.whatsapp.domain.WebhookSignature;
import br.com.branlycrm.whatsapp.meta.MetaGraphClient;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.repositories.OutboundChannelRepository;
import br.com.branlycrm.whatsapp.repositories.OutboxRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import br.com.branlycrm.whatsapp.services.InboundProcessingService;
import br.com.branlycrm.whatsapp.services.WhatsappWebhookService;
import br.com.branlycrm.whatsapp.worker.OutboundSender;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
import tools.jackson.databind.JsonNode;

/**
 * Replies over real HTTP (CRM-084a, ADR-005): queued by the API with its rules, sent by the worker
 * against a fake Graph API, and reconciled by delivery statuses from the signed webhook. An
 * uncertain send is never resent. Synthetic data only.
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
      "app.whatsapp.meta.timeout-seconds=1",
      MfaFixture.KEY_PROPERTY
    })
class WhatsappOutboundIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory("branly-outbound-it-");
  private static final Path FILES = temporaryDirectory("branly-outbound-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String LOGIN_PASSWORD = "Synthetic outbound secret 2026!";
  private static final String INBOX = "/v1/clinic/inbox/conversations";
  private static final FakeGraph GRAPH = new FakeGraph();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired ChannelSealer sealer;
  @Autowired MetaGraphClient meta;
  @Autowired OutboxRepository outbox;
  @Autowired ChannelRegistryRepository channels;
  @Autowired WhatsappWebhookService webhook;
  @Autowired InboundProcessingService inbound;
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
  void repliesAreQueuedSentAndReconciled(CapturedOutput output) throws Exception {
    UUID ownerA = account("owner-a@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(account("owner-b@example.test"));
    UUID first = account("attendant-1@example.test");
    UUID second = account("attendant-2@example.test");
    member(clinicA, first, "ATTENDANT");
    member(clinicA, second, "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser one = selected("attendant-1@example.test", clinicA);
    Browser two = selected("attendant-2@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);
    UUID channel = channel(clinicA, "1001", "2001");
    UUID open = conversation(clinicA, channel, "+5583999000001", 60);
    UUID closed = conversation(clinicA, channel, "+5583999000002", 25 * 3600);
    OutboundSender sender = sender();

    // Queued once per key; the author takes the conversation.
    String key = UUID.randomUUID().toString();
    var queued = reply(one, open, key, "Olá! Podemos agendar para sexta.");
    assertThat(queued.statusCode()).as(queued.body()).isEqualTo(202);
    JsonNode item = json(queued);
    assertThat(item.get("status").asText()).isEqualTo("QUEUED");
    assertThat(json(reply(one, open, key, "Olá! Podemos agendar para sexta.")).get("id"))
        .isEqualTo(item.get("id"));
    assertThat(scalar(clinicA, "SELECT count(*) FROM whatsapp_outbox")).isEqualTo("1");
    assertThat(json(one.get(INBOX + "/" + open)).get("assigneeId").asText())
        .isEqualTo(first.toString());
    assertThat(pendingOutbound(channel)).isTrue();

    // Rules: someone else's conversation, the window, roles, clinics and the same key elsewhere.
    assertThat(reply(two, open, UUID.randomUUID().toString(), "eu também").body())
        .contains("CONVERSATION_TAKEN");
    assertThat(reply(owner, closed, UUID.randomUUID().toString(), "oi").body())
        .contains("CONVERSATION_WINDOW_CLOSED");
    assertThat(reply(viewer, open, UUID.randomUUID().toString(), "oi").statusCode()).isEqualTo(403);
    assertThat(reply(ownerB, open, UUID.randomUUID().toString(), "oi").statusCode()).isEqualTo(404);
    assertThat(reply(one, closed, key, "outra").body())
        .contains("CONVERSATION_IDEMPOTENCY_CONFLICT");
    assertThat(reply(one, open, UUID.randomUUID().toString(), " ").statusCode()).isEqualTo(400);

    // The worker sends it: SENT, and the message joins the conversation.
    assertThat(sender.drain(channel)).isFalse();
    assertThat(GRAPH.sent).hasSize(1);
    assertThat(GRAPH.sent.getFirst()).contains("\"to\":\"5583999000001\"");
    assertThat(GRAPH.sent.getFirst())
        .contains("\"biz_opaque_callback_data\":\"" + item.get("id").asText());
    JsonNode messages = json(one.get(INBOX + "/" + open + "/messages"));
    JsonNode last = messages.get("items").get(messages.get("items").size() - 1);
    assertThat(last.get("direction").asText()).isEqualTo("OUT");
    assertThat(last.get("status").asText()).isEqualTo("SENT");
    assertThat(last.get("body").asText()).isEqualTo("Olá! Podemos agendar para sexta.");
    assertThat(json(one.get(INBOX + "/" + open + "/outbox")).size()).isZero();
    assertThat(pendingOutbound(channel)).isFalse();

    // The owner may answer someone else's conversation. A timeout is uncertain, never resent.
    JsonNode slow = json(reply(owner, open, UUID.randomUUID().toString(), "demora"));
    sender.drain(channel);
    assertThat(GRAPH.sent).hasSize(2);
    JsonNode pending = json(one.get(INBOX + "/" + open + "/outbox"));
    assertThat(pending.get(0).get("status").asText()).isEqualTo("UNCERTAIN");
    sender.drain(channel);
    assertThat(GRAPH.sent).hasSize(2);

    // A status carrying its id reconciles it: SENT, with the message in the conversation.
    status("wamid.LATE", "sent", slow.get("id").asText());
    inbound.drain(channel);
    assertThat(
            scalar(
                clinicA,
                "SELECT status || ':' || wamid FROM whatsapp_outbox WHERE id = '"
                    + slow.get("id").asText()
                    + "'"))
        .isEqualTo("SENT:wamid.LATE");
    assertThat(scalar(clinicA, "SELECT count(*) FROM whatsapp_messages WHERE wamid = 'wamid.LATE'"))
        .isEqualTo("1");

    // Delivery statuses move forward only; a refusal fails with Meta's code.
    status("wamid.OUT-1", "read", null);
    status("wamid.OUT-1", "delivered", null);
    inbound.drain(channel);
    assertThat(scalar(clinicA, "SELECT status FROM whatsapp_messages WHERE wamid = 'wamid.OUT-1'"))
        .isEqualTo("READ");
    reply(owner, open, UUID.randomUUID().toString(), "recusa");
    sender.drain(channel);
    JsonNode refused = json(one.get(INBOX + "/" + open + "/outbox"));
    assertThat(refused.get(0).get("status").asText()).isEqualTo("FAILED");
    assertThat(refused.get(0).get("errorCode").asText()).isEqualTo("META_131047");

    // Clinic B sees none of it; no text or token reached the log.
    assertThat(ownerB.get(INBOX + "/" + open + "/outbox").statusCode()).isEqualTo(404);
    assertThat(output.getAll())
        .doesNotContain("Podemos agendar")
        .doesNotContain("TOKEN-A")
        .doesNotContain("5583999000001");
  }

  private OutboundSender sender() {
    DriverManagerDataSource role =
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()), WHATSAPP, PASSWORD);
    JdbcTemplate worker = new JdbcTemplate(role);
    return new OutboundSender(
        new TenantRegistryRepository(worker),
        new ChannelRegistryRepository(worker),
        new OutboundChannelRepository(worker),
        outbox,
        new ChannelUnsealer(FILES.resolve("keys/private").toString()),
        meta,
        context.getBeanProvider(Clock.class));
  }

  private void status(String wamid, String value, String callback) {
    String body =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\",\"changes\":[{"
            + "\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"2001\"},"
            + "\"statuses\":[{\"id\":\""
            + wamid
            + "\",\"status\":\""
            + value
            + "\",\"timestamp\":\"1763380001\""
            + (callback == null ? "" : ",\"biz_opaque_callback_data\":\"" + callback + "\"")
            + "}]}}]}]}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    webhook.receive(
        bytes, WebhookSignature.sign(APP_SECRET.getBytes(StandardCharsets.UTF_8), bytes));
  }

  private static HttpResponse<String> reply(
      Browser browser, UUID conversation, String key, String text) throws Exception {
    return browser.postJson(
        INBOX + "/" + conversation + "/messages",
        "{\"idempotencyKey\":\"" + key + "\",\"text\":\"" + text + "\"}",
        true);
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isBetween(200, 202);
    return Browser.JSON.readTree(response.body());
  }

  private UUID channel(UUID tenant, String waba, String phone) {
    UUID id = UUID.randomUUID();
    SealedSecret token = sealer.seal(id, ChannelSecretPurpose.ACCESS_TOKEN, "TOKEN-A");
    jdbc.update(
        "INSERT INTO whatsapp_channels (id, tenant_id, status, waba_id, phone_number_id,"
            + " sealed_token, key_version, connected_by, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, now(), now())",
        id,
        tenant,
        waba,
        phone,
        token.value(),
        token.keyVersion(),
        UUID.randomUUID());
    return id;
  }

  private boolean pendingOutbound(UUID channel) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT outbound_pending_since IS NOT NULL FROM whatsapp_channels WHERE id = ?",
            Boolean.class,
            channel));
  }

  /** A WhatsApp contact and its conversation, whose last inbound message was secondsAgo. */
  private UUID conversation(UUID tenant, UUID channel, String phone, int secondsAgo)
      throws SQLException {
    UUID contact = UUID.randomUUID();
    UUID conversation = UUID.randomUUID();
    clinic(
        tenant,
        "INSERT INTO contacts (id, name, name_search, phone_e164, created_via, created_at,"
            + " updated_at) VALUES ('"
            + contact
            + "', 'Sintético', 'sintetico', '"
            + phone
            + "', 'WHATSAPP', now(), now());"
            + "INSERT INTO whatsapp_conversations (id, contact_id, channel_id, created_at,"
            + " last_message_at, last_inbound_at) VALUES ('"
            + conversation
            + "', '"
            + contact
            + "', '"
            + channel
            + "', now(), now() - interval '"
            + secondsAgo
            + " seconds', now() - interval '"
            + secondsAgo
            + " seconds')");
    return conversation;
  }

  private String scalar(UUID tenant, String sql) throws SQLException {
    try (Connection connection = clinicConnection(tenant);
        Statement statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private void clinic(UUID tenant, String sql) throws SQLException {
    try (Connection connection = clinicConnection(tenant);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private Connection clinicConnection(UUID tenant) throws SQLException {
    String database =
        jdbc.queryForObject(
            "SELECT database_name FROM tenants WHERE tenant_id = ?", String.class, tenant);
    return DriverManager.getConnection(
        TestPostgres.url(POSTGRES, database), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, jdbc);
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

  private UUID provision(UUID owner) {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
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

  /** Sends: "demora" times out, "recusa" is outside the window, anything else is accepted. */
  static final class FakeGraph {
    final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    private HttpServer server;

    void start() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
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
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!exchange.getRequestURI().getPath().equals("/v25.0/2001/messages")
          || !"Bearer TOKEN-A".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        reply(exchange, 400, "{\"error\":{\"code\":100}}");
        return;
      }
      sent.add(body);
      if (body.contains("\"body\":\"demora\"")) {
        try {
          Thread.sleep(2500);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        reply(exchange, 200, "{\"messages\":[{\"id\":\"wamid.NEVER\"}]}");
      } else if (body.contains("\"body\":\"recusa\"")) {
        reply(exchange, 400, "{\"error\":{\"code\":131047}}");
      } else {
        reply(
            exchange, 200, "{\"messages\":[{\"id\":\"wamid.OUT-" + ids.incrementAndGet() + "\"}]}");
      }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      try {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (IOException closed) {
        // The client gave up (timeout).
      } finally {
        exchange.close();
      }
    }
  }
}
