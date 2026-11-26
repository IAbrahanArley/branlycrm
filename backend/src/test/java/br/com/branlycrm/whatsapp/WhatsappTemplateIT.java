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
import br.com.branlycrm.whatsapp.repositories.TemplateRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import br.com.branlycrm.whatsapp.services.WhatsappWebhookService;
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import br.com.branlycrm.whatsapp.worker.OutboundSender;
import br.com.branlycrm.whatsapp.worker.TemplateSynchronizer;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Message templates (CRM-085): copied from a fake Graph API, listed to the clinic, sent outside the
 * 24-hour window with Meta's rules for the values, refused when not approved, not supported, to an
 * opted-out contact (marketing) or past the daily limit, and refused again by the worker when
 * paused after queuing. Other clinics never reach them. Synthetic data.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S",
      "app.whatsapp.meta.app-id=1234",
      "app.whatsapp.templates.daily-limit=3",
      MfaFixture.KEY_PROPERTY
    })
class WhatsappTemplateIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory("branly-template-it-");
  private static final Path FILES = temporaryDirectory("branly-template-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String LOGIN_PASSWORD = "Synthetic template secret 2026!";
  private static final String INBOX = "/v1/clinic/inbox/conversations";
  private static final String TEMPLATES = "/v1/clinic/inbox/templates";
  private static final FakeGraph GRAPH = new FakeGraph();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired ChannelSealer sealer;
  @Autowired MetaGraphClient meta;
  @Autowired OutboxRepository outbox;
  @Autowired TemplateRepository templates;
  @Autowired MediaStorage storage;
  @Autowired WhatsappWebhookService webhook;
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
  void approvedTemplatesAreSyncedAndSentOutsideTheWindowWithinTheRules() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);
    UUID channel = channel(clinicA, "1001", "2001");
    // The last message of the patient was two days ago: the window is closed.
    UUID conversation = conversation(clinicA, channel, "+5583999000001");
    OutboundSender sender = sender();
    TemplateSynchronizer synchronizer = synchronizer();

    // Copied from Meta: never synced, so due at once; listed with what each one needs.
    GRAPH.reminderStatus.set("APPROVED");
    assertThat(synchronizer.syncDue()).isEqualTo(1);
    assertThat(GRAPH.templateReads.get()).isEqualTo(2);
    assertThat(synchronizer.syncDue()).isZero();
    JsonNode list = json(viewer.get(TEMPLATES));
    assertThat(list).hasSize(4);
    JsonNode reminder = byName(list, "retorno_consulta");
    assertThat(reminder.get("sendable").asBoolean()).isTrue();
    assertThat(reminder.get("bodyParams").toString()).isEqualTo("[\"1\",\"2\"]");
    assertThat(byName(list, "com_imagem").get("supported").asBoolean()).isFalse();
    assertThat(byName(list, "pendente").get("sendable").asBoolean()).isFalse();
    String reminderId = reminder.get("id").asText();
    String promoId = byName(list, "promo").get("id").asText();
    assertThat(json(ownerB.get(TEMPLATES))).isEmpty();

    // Free text stays refused outside the window; an approved template goes.
    var text =
        owner.postJson(
            INBOX + "/" + conversation + "/messages",
            "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"text\":\"Oi\"}",
            true);
    assertThat(text.body()).contains("CONVERSATION_WINDOW_CLOSED");
    String key = UUID.randomUUID().toString();
    var queued = template(owner, conversation, key, reminderId, "{\"1\":\"Ana\",\"2\":\"amanhã\"}");
    assertThat(queued.statusCode()).as(queued.body()).isEqualTo(202);
    JsonNode item = Browser.JSON.readTree(queued.body());
    assertThat(item.get("kind").asText()).isEqualTo("TEMPLATE");
    assertThat(item.get("text").asText())
        .isEqualTo("Lembrete\n\nOlá Ana, sua consulta é amanhã.\n\nClínica sintética");
    assertThat(
            Browser.JSON
                .readTree(
                    template(
                            owner,
                            conversation,
                            key,
                            reminderId,
                            "{\"1\":\"Ana\",\"2\":\"amanhã\"}")
                        .body())
                .get("id"))
        .isEqualTo(item.get("id"));

    // Values with Meta's rules, and templates that cannot be sent.
    var missing = template(owner, conversation, null, reminderId, "{\"1\":\"Ana\"}");
    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.body()).contains("body.2");
    assertThat(
            template(owner, conversation, null, reminderId, "{\"1\":\"Ana\\nB\",\"2\":\"x\"}")
                .statusCode())
        .isEqualTo(400);
    String imageId = byName(list, "com_imagem").get("id").asText();
    assertThat(template(owner, conversation, null, imageId, "{}").body())
        .contains("CONVERSATION_TEMPLATE_NOT_APPROVED");
    assertThat(
            template(owner, conversation, null, byName(list, "pendente").get("id").asText(), "{}")
                .statusCode())
        .isEqualTo(409);
    assertThat(template(owner, conversation, null, UUID.randomUUID().toString(), "{}").statusCode())
        .isEqualTo(404);
    assertThat(
            template(viewer, conversation, null, reminderId, "{\"1\":\"A\",\"2\":\"B\"}")
                .statusCode())
        .isEqualTo(403);
    assertThat(
            template(ownerB, conversation, null, reminderId, "{\"1\":\"A\",\"2\":\"B\"}")
                .statusCode())
        .isEqualTo(404);

    // The worker sends it: the template, the language and the values in order.
    sender.drain(channel);
    JsonNode sent = Browser.JSON.readTree(GRAPH.messages.getLast());
    assertThat(sent.get("type").asText()).isEqualTo("template");
    assertThat(sent.at("/template/name").asText()).isEqualTo("retorno_consulta");
    assertThat(sent.at("/template/language/code").asText()).isEqualTo("pt_BR");
    assertThat(sent.at("/template/components/0/type").asText()).isEqualTo("body");
    assertThat(sent.at("/template/components/0/parameters/1/text").asText()).isEqualTo("amanhã");
    assertThat(sent.get("biz_opaque_callback_data").asText()).isEqualTo(item.get("id").asText());
    assertThat(
            scalar(
                clinicA,
                "SELECT status FROM whatsapp_outbox WHERE id = '" + item.get("id").asText() + "'"))
        .isEqualTo("SENT");
    assertThat(
            scalar(clinicA, "SELECT message_type || ':' || left(body, 8) FROM whatsapp_messages"))
        .isEqualTo("template:Lembrete");

    // Paused after queuing: the webhook asks for a new copy and the worker refuses it.
    var second = template(owner, conversation, null, reminderId, "{\"1\":\"Bia\",\"2\":\"sexta\"}");
    assertThat(second.statusCode()).as(second.body()).isEqualTo(202);
    String secondId = Browser.JSON.readTree(second.body()).get("id").asText();
    GRAPH.reminderStatus.set("PAUSED");
    templateEvent();
    assertThat(
            jdbc.queryForObject(
                "SELECT templates_sync_requested_at IS NOT NULL FROM whatsapp_channels WHERE id = ?",
                Boolean.class,
                channel))
        .isTrue();
    assertThat(synchronizer.syncDue()).isEqualTo(1);
    int before = GRAPH.messages.size();
    sender.drain(channel);
    assertThat(GRAPH.messages).hasSize(before);
    assertThat(
            scalar(clinicA, "SELECT error_code FROM whatsapp_outbox WHERE id = '" + secondId + "'"))
        .isEqualTo("TEMPLATE_NOT_APPROVED");
    assertThat(template(owner, conversation, null, reminderId, "{\"1\":\"A\",\"2\":\"B\"}").body())
        .contains("CONVERSATION_TEMPLATE_NOT_APPROVED");
    // The first request repeated with its key still gets what it queued.
    var repeated =
        template(owner, conversation, key, reminderId, "{\"1\":\"Ana\",\"2\":\"amanhã\"}");
    assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(202);
    assertThat(Browser.JSON.readTree(repeated.body()).get("id")).isEqualTo(item.get("id"));

    // Named marketing template: refused to an opted-out contact, then the daily limit (3).
    var promo = template(owner, conversation, null, promoId, "{\"nome\":\"Ana\"}");
    assertThat(promo.statusCode()).as(promo.body()).isEqualTo(202);
    var limited = template(owner, conversation, null, promoId, "{\"nome\":\"Ana\"}");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.body()).contains("CONVERSATION_TEMPLATE_LIMIT");
    assertThat(scalar(clinicA, "SELECT queued FROM whatsapp_template_usage")).isEqualTo("3");
    clinic(
        clinicA,
        "INSERT INTO contact_consents (id, contact_id, channel, status, source, recorded_at)"
            + " SELECT gen_random_uuid(), contact_id, 'WHATSAPP', 'OPT_OUT', 'WHATSAPP', now()"
            + " FROM whatsapp_conversations");
    assertThat(template(owner, conversation, null, promoId, "{\"nome\":\"Ana\"}").body())
        .contains("CONVERSATION_TEMPLATE_OPTED_OUT");

    // "Sync now": the owner asks; a viewer cannot; a clinic without a channel is told so.
    assertThat(owner.post(TEMPLATES + "/sync", true).statusCode()).isEqualTo(202);
    assertThat(viewer.post(TEMPLATES + "/sync", true).statusCode()).isEqualTo(403);
    assertThat(ownerB.post(TEMPLATES + "/sync", true).body())
        .contains("CONVERSATION_CHANNEL_NOT_CONNECTED");
  }

  private static HttpResponse<String> template(
      Browser browser, UUID conversation, String key, String templateId, String body)
      throws Exception {
    return browser.postJson(
        INBOX + "/" + conversation + "/messages/template",
        "{\"idempotencyKey\":\""
            + (key == null ? UUID.randomUUID().toString() : key)
            + "\",\"templateId\":\""
            + templateId
            + "\",\"body\":"
            + body
            + "}",
        true);
  }

  private static JsonNode byName(JsonNode list, String name) {
    for (JsonNode item : list) {
      if (name.equals(item.get("name").asText())) return item;
    }
    throw new AssertionError("template not listed: " + name);
  }

  private void templateEvent() {
    String body =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\",\"time\":1763380002,"
            + "\"changes\":[{\"field\":\"message_template_status_update\",\"value\":{"
            + "\"event\":\"PAUSED\",\"message_template_id\":101,"
            + "\"message_template_name\":\"retorno_consulta\","
            + "\"message_template_language\":\"pt_BR\"}}]}]}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    webhook.receive(
        bytes, WebhookSignature.sign(APP_SECRET.getBytes(StandardCharsets.UTF_8), bytes));
  }

  private JdbcTemplate workerJdbc() {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()), WHATSAPP, PASSWORD));
  }

  private OutboundSender sender() {
    JdbcTemplate worker = workerJdbc();
    return new OutboundSender(
        new TenantRegistryRepository(worker),
        new ChannelRegistryRepository(worker),
        new OutboundChannelRepository(worker),
        outbox,
        new ChannelUnsealer(FILES.resolve("keys/private").toString()),
        meta,
        storage,
        context.getBeanProvider(Clock.class));
  }

  private TemplateSynchronizer synchronizer() {
    JdbcTemplate worker = workerJdbc();
    return new TemplateSynchronizer(
        new TenantRegistryRepository(worker),
        new ChannelRegistryRepository(worker),
        new OutboundChannelRepository(worker),
        templates,
        new ChannelUnsealer(FILES.resolve("keys/private").toString()),
        meta,
        context.getBeanProvider(Clock.class));
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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

  /** A WhatsApp contact and its conversation, whose last inbound message was two days ago. */
  private UUID conversation(UUID tenant, UUID channel, String phone) throws SQLException {
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
            + "', now(), now() - interval '2 days', now() - interval '2 days')");
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

  /**
   * Graph template list (two pages, with Meta's cursor) and message endpoints; records what they
   * received.
   */
  static final class FakeGraph {
    final List<String> messages = new CopyOnWriteArrayList<>();
    final AtomicReference<String> reminderStatus = new AtomicReference<>("APPROVED");
    final AtomicInteger templateReads = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
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
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getRawQuery();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!"Bearer TOKEN-A".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        reply(exchange, 401, "{\"error\":{\"code\":190}}");
        return;
      }
      if (path.endsWith("/1001/message_templates")) {
        templateReads.incrementAndGet();
        if (query == null || !query.contains("after=")) {
          reply(
              exchange,
              200,
              "{\"data\":["
                  + "{\"id\":\"101\",\"name\":\"retorno_consulta\",\"language\":\"pt_BR\","
                  + "\"status\":\""
                  + reminderStatus.get()
                  + "\",\"category\":\"UTILITY\",\"components\":["
                  + "{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"Lembrete\"},"
                  + "{\"type\":\"BODY\",\"text\":\"Olá {{1}}, sua consulta é {{2}}.\"},"
                  + "{\"type\":\"FOOTER\",\"text\":\"Clínica sintética\"},"
                  + "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Ok\"}]}]},"
                  + "{\"id\":\"102\",\"name\":\"promo\",\"language\":\"pt_BR\",\"status\":\"APPROVED\","
                  + "\"category\":\"MARKETING\",\"parameter_format\":\"NAMED\",\"components\":["
                  + "{\"type\":\"BODY\",\"text\":\"Oi {{nome}}, temos novidades!\"}]}],"
                  + "\"paging\":{\"cursors\":{\"after\":\"QVFI\"},\"next\":\"more\"}}");
          return;
        }
        reply(
            exchange,
            200,
            "{\"data\":["
                + "{\"id\":\"103\",\"name\":\"com_imagem\",\"language\":\"pt_BR\",\"status\":\"APPROVED\","
                + "\"category\":\"UTILITY\",\"components\":[{\"type\":\"HEADER\",\"format\":\"IMAGE\"},"
                + "{\"type\":\"BODY\",\"text\":\"Veja\"}]},"
                + "{\"id\":\"104\",\"name\":\"pendente\",\"language\":\"pt_BR\",\"status\":\"PENDING\","
                + "\"category\":\"UTILITY\",\"components\":[{\"type\":\"BODY\",\"text\":\"Oi\"}]}],"
                + "\"paging\":{\"cursors\":{\"before\":\"QVFI\"}}}");
        return;
      }
      if (path.endsWith("/2001/messages")) {
        messages.add(body);
        reply(
            exchange,
            200,
            "{\"messages\":[{\"id\":\"wamid.OUT-" + sent.incrementAndGet() + "\"}]}");
        return;
      }
      reply(exchange, 404, "{\"error\":{\"code\":100}}");
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }
  }
}
