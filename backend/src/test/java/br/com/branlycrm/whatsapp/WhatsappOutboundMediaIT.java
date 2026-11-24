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
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import br.com.branlycrm.whatsapp.worker.OutboundSender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Files sent from the inbox (CRM-084b2): checked by their first bytes, stored in a real S3 API
 * (Adobe S3Mock), queued like a text reply, uploaded once to a fake Graph API and sent; once sent
 * the file is in the history with its link. Other clinics never reach it. Synthetic data.
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
      "app.whatsapp.media.bucket=branly-media",
      "app.whatsapp.media.region=sa-saopaulo-1",
      MfaFixture.KEY_PROPERTY
    })
class WhatsappOutboundMediaIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>(
              DockerImageName.parse(
                  "adobe/s3mock@sha256:468ba0b0e87f20ad54fbb6085e98c143f5bd1e384a844fa12a58535b4e39d4aa"))
          .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "branly-media")
          .withExposedPorts(9090)
          .waitingFor(Wait.forListeningPort());

  private static final Path SECRETS = temporaryDirectory("branly-send-media-it-");
  private static final Path FILES = temporaryDirectory("branly-send-media-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String LOGIN_PASSWORD = "Synthetic send media secret 2026!";
  private static final String INBOX = "/v1/clinic/inbox/conversations";
  private static final byte[] PDF = pdf();
  private static final FakeGraph GRAPH = new FakeGraph();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired ChannelSealer sealer;
  @Autowired MetaGraphClient meta;
  @Autowired OutboxRepository outbox;
  @Autowired MediaStorage storage;
  @Autowired WhatsappWebhookService webhook;
  @Autowired InboundProcessingService inbound;
  @Autowired ApplicationContext context;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    Files.writeString(FILES.resolve("app-secret"), APP_SECRET);
    Files.createDirectories(FILES.resolve("storage"));
    Files.writeString(FILES.resolve("storage/access-key-id"), "synthetic-key");
    Files.writeString(FILES.resolve("storage/secret-access-key"), "synthetic-secret");
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
    registry.add(
        "app.whatsapp.media.endpoint",
        () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9090));
    registry.add(
        "app.whatsapp.media.credentials-directory", () -> FILES.resolve("storage").toString());
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
  void sentFileIsCheckedStoredUploadedOnceAndSent() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);
    UUID channel = channel(clinicA, "1001", "2001");
    UUID conversation = conversation(clinicA, channel, "+5583999000001");
    String send = INBOX + "/" + conversation + "/messages/media";
    OutboundSender sender = sender();

    // Queued once per key, with the detected type and a sanitized document name.
    String key = UUID.randomUUID().toString();
    Map<String, String> fields = Map.of("idempotencyKey", key, "caption", "Segue o pedido");
    var queued = owner.postMultipart(send, fields, "..\\pasta/Pedido Março\".pdf", PDF);
    assertThat(queued.statusCode()).as(queued.body()).isEqualTo(202);
    JsonNode item = Browser.JSON.readTree(queued.body());
    assertThat(item.get("kind").asText()).isEqualTo("MEDIA");
    assertThat(item.get("status").asText()).isEqualTo("QUEUED");
    assertThat(item.get("contentType").asText()).isEqualTo("application/pdf");
    assertThat(item.get("fileName").asText()).isEqualTo("Pedido Março.pdf");
    assertThat(item.get("text").asText()).isEqualTo("Segue o pedido");
    var again = owner.postMultipart(send, fields, "outro.pdf", PDF);
    assertThat(Browser.JSON.readTree(again.body()).get("id")).isEqualTo(item.get("id"));
    assertThat(scalar(clinicA, "SELECT count(*) FROM whatsapp_media")).isEqualTo("1");
    assertThat(scalar(clinicA, "SELECT storage_key FROM whatsapp_media"))
        .startsWith("clinics/" + clinicA + "/media/");

    // Refused before anything is stored.
    assertThat(
            owner
                .postMultipart(
                    send,
                    Map.of("idempotencyKey", UUID.randomUUID().toString()),
                    "x.pdf",
                    "MZ\u0090\0executable".getBytes(StandardCharsets.ISO_8859_1))
                .body())
        .contains("CONVERSATION_MEDIA_TYPE_NOT_ALLOWED");
    assertThat(
            owner
                .postMultipart(
                    send,
                    Map.of("idempotencyKey", UUID.randomUUID().toString()),
                    "x.webp",
                    "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))
                .statusCode())
        .isEqualTo(415);
    var audioCaption =
        owner.postMultipart(
            send,
            Map.of("idempotencyKey", UUID.randomUUID().toString(), "caption", "oi"),
            "a.mp3",
            "ID3\3\0audio".getBytes(StandardCharsets.ISO_8859_1));
    assertThat(audioCaption.statusCode()).isEqualTo(400);
    assertThat(audioCaption.body()).contains("caption");
    var large =
        owner.postMultipart(
            send, Map.of("idempotencyKey", UUID.randomUUID().toString()), "foto.jpg", bigJpeg());
    assertThat(large.statusCode()).isEqualTo(413);
    assertThat(large.body()).contains("CONVERSATION_MEDIA_TOO_LARGE");
    assertThat(
            owner
                .postMultipart(
                    send, Map.of("idempotencyKey", UUID.randomUUID().toString()), null, null)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            viewer
                .postMultipart(
                    send, Map.of("idempotencyKey", UUID.randomUUID().toString()), "a.pdf", PDF)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            ownerB
                .postMultipart(
                    send, Map.of("idempotencyKey", UUID.randomUUID().toString()), "a.pdf", PDF)
                .statusCode())
        .isEqualTo(404);
    assertThat(scalar(clinicA, "SELECT count(*) FROM whatsapp_media")).isEqualTo("1");
    assertThat(scalar(clinicB, "SELECT count(*) FROM whatsapp_media")).isEqualTo("0");

    // First attempt: uploaded, but the message hits a rate limit; retried without a new upload.
    GRAPH.rateLimitNextMessage.set(true);
    sender.drain(channel);
    assertThat(GRAPH.uploads).hasSize(1);
    assertThat(GRAPH.uploads.getFirst())
        .contains("name=\"type\"\r\n\r\napplication/pdf")
        .contains("filename=\"Pedido Março.pdf\"")
        .contains("%PDF-1.7");
    assertThat(scalar(clinicA, "SELECT status FROM whatsapp_outbox")).isEqualTo("QUEUED");
    assertThat(scalar(clinicA, "SELECT meta_media_id FROM whatsapp_media")).isEqualTo("up-1");
    clinic(clinicA, "UPDATE whatsapp_outbox SET next_attempt_at = now()");
    sender.drain(channel);
    assertThat(GRAPH.uploads).hasSize(1);
    JsonNode message = Browser.JSON.readTree(GRAPH.messages.getLast());
    assertThat(message.get("type").asText()).isEqualTo("document");
    assertThat(message.get("to").asText()).isEqualTo("5583999000001");
    assertThat(message.at("/document/id").asText()).isEqualTo("up-1");
    assertThat(message.at("/document/caption").asText()).isEqualTo("Segue o pedido");
    assertThat(message.at("/document/filename").asText()).isEqualTo("Pedido Março.pdf");
    assertThat(message.get("biz_opaque_callback_data").asText()).isEqualTo(item.get("id").asText());
    assertThat(scalar(clinicA, "SELECT status FROM whatsapp_outbox")).isEqualTo("SENT");

    // In the history like a received file: type, caption, stored, and the link downloads it.
    JsonNode sent = null;
    for (JsonNode each : json(owner.get(INBOX + "/" + conversation + "/messages")).get("items")) {
      if ("OUT".equals(each.get("direction").asText())) sent = each;
    }
    assertThat(sent).isNotNull();
    assertThat(sent.get("type").asText()).isEqualTo("document");
    assertThat(sent.get("body").asText()).isEqualTo("Segue o pedido");
    assertThat(sent.get("mediaStatus").asText()).isEqualTo("STORED");
    assertThat(sent.get("mediaMime").asText()).isEqualTo("application/pdf");
    String link = INBOX + "/" + conversation + "/messages/" + sent.get("id").asText() + "/media";
    String url = json(viewer.get(link)).get("url").asText();
    HttpResponse<byte[]> file =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertThat(file.body()).isEqualTo(PDF);
    assertThat(ownerB.get(link).statusCode()).isEqualTo(404);

    // A send that may have reached Meta: UNCERTAIN, never resent; a status reconciles it and the
    // image joins the history with its file (ADR-005).
    var image =
        owner.postMultipart(
            send, Map.of("idempotencyKey", UUID.randomUUID().toString()), "foto.png", png());
    assertThat(image.statusCode()).as(image.body()).isEqualTo(202);
    String imageId = Browser.JSON.readTree(image.body()).get("id").asText();
    assertThat(Browser.JSON.readTree(image.body()).has("fileName")).isTrue();
    assertThat(Browser.JSON.readTree(image.body()).get("fileName").isNull()).isTrue();
    GRAPH.serverErrorNextMessage.set(true);
    int messagesBefore = GRAPH.messages.size();
    sender.drain(channel);
    assertThat(GRAPH.messages).hasSize(messagesBefore + 1);
    assertThat(Browser.JSON.readTree(GRAPH.messages.getLast()).get("type").asText())
        .isEqualTo("image");
    assertThat(Browser.JSON.readTree(GRAPH.messages.getLast()).at("/image/caption").isMissingNode())
        .isTrue();
    assertThat(scalar(clinicA, "SELECT status FROM whatsapp_outbox WHERE id = '" + imageId + "'"))
        .isEqualTo("UNCERTAIN");
    sender.drain(channel);
    assertThat(GRAPH.messages).hasSize(messagesBefore + 1);
    status("wamid.LATE", "sent", imageId);
    inbound.drain(channel);
    assertThat(scalar(clinicA, "SELECT status FROM whatsapp_outbox WHERE id = '" + imageId + "'"))
        .isEqualTo("SENT");
    assertThat(
            scalar(
                clinicA,
                "SELECT m.message_type || ':' || md.content_type FROM whatsapp_media md"
                    + " JOIN whatsapp_messages m ON m.id = md.message_id"
                    + " WHERE m.wamid = 'wamid.LATE'"))
        .isEqualTo("image:image/png");
  }

  private void status(String wamid, String value, String callback) {
    String body =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\",\"changes\":[{"
            + "\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"2001\"},"
            + "\"statuses\":[{\"id\":\""
            + wamid
            + "\",\"status\":\""
            + value
            + "\",\"timestamp\":\"1763380001\",\"biz_opaque_callback_data\":\""
            + callback
            + "\"}]}}]}]}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    webhook.receive(
        bytes, WebhookSignature.sign(APP_SECRET.getBytes(StandardCharsets.UTF_8), bytes));
  }

  /** A PNG signature followed by synthetic bytes. */
  private static byte[] png() {
    byte[] bytes = new byte[1024];
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, bytes, 0, signature.length);
    for (int i = signature.length; i < bytes.length; i++) bytes[i] = (byte) i;
    return bytes;
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
        storage,
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

  /** A WhatsApp contact and its conversation, with an inbound message a minute ago. */
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
            + "', now(), now() - interval '1 minute', now() - interval '1 minute')");
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

  /** A PDF head followed by synthetic text. */
  private static byte[] pdf() {
    return "%PDF-1.7\nsynthetic document body for the test\n%%EOF\n"
        .getBytes(StandardCharsets.US_ASCII);
  }

  /** A JPEG head over the 5 MB image limit (under the 16 MB request limit). */
  private static byte[] bigJpeg() {
    byte[] bytes = new byte[5 * 1024 * 1024 + 10];
    bytes[0] = (byte) 0xFF;
    bytes[1] = (byte) 0xD8;
    bytes[2] = (byte) 0xFF;
    return bytes;
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

  /** Graph upload and message endpoints; records what they received. */
  static final class FakeGraph {
    final List<String> uploads = new CopyOnWriteArrayList<>();
    final List<String> messages = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicBoolean rateLimitNextMessage =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean serverErrorNextMessage =
        new java.util.concurrent.atomic.AtomicBoolean();
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
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (!"Bearer TOKEN-A".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        reply(exchange, 401, "{\"error\":{\"code\":190}}");
        return;
      }
      if (path.endsWith("/2001/media")) {
        uploads.add(body);
        reply(exchange, 200, "{\"id\":\"up-" + uploads.size() + "\"}");
        return;
      }
      if (path.endsWith("/2001/messages")) {
        messages.add(body);
        if (rateLimitNextMessage.getAndSet(false)) {
          reply(exchange, 429, "{\"error\":{\"code\":130429}}");
          return;
        }
        if (serverErrorNextMessage.getAndSet(false)) {
          reply(exchange, 500, "{}");
          return;
        }
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
