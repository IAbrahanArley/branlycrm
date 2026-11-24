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
import br.com.branlycrm.whatsapp.repositories.MediaRepository;
import br.com.branlycrm.whatsapp.repositories.OutboundChannelRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelSecretPurpose;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.secrets.SealedSecret;
import br.com.branlycrm.whatsapp.services.InboundProcessingService;
import br.com.branlycrm.whatsapp.services.WhatsappWebhookService;
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import br.com.branlycrm.whatsapp.worker.MediaDownloader;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * Received media (CRM-084b1): registered with the message, downloaded by the worker from a fake
 * Graph API, checked by its first bytes and stored in a real S3 API (Adobe S3Mock); the link is
 * temporary, downloads as an attachment and is reachable only through the clinic. Synthetic data.
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
class WhatsappMediaIT {
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

  private static final Path SECRETS = temporaryDirectory("branly-media-it-");
  private static final Path FILES = temporaryDirectory("branly-media-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String LOGIN_PASSWORD = "Synthetic media secret 2026!";
  private static final String INBOX = "/v1/clinic/inbox/conversations";
  private static final byte[] PNG = png();
  private static final FakeGraph GRAPH =
      new FakeGraph(
          Map.of(
              "m-png", PNG,
              "m-exe", "MZ\u0090\0not allowed".getBytes(StandardCharsets.ISO_8859_1),
              "m-big", big()));

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;
  @Autowired ChannelSealer sealer;
  @Autowired MetaGraphClient meta;
  @Autowired MediaRepository media;
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
  void receivedMediaIsCheckedStoredAndLinked() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);
    UUID channel = channel(clinicA, "1001", "2001");
    MediaDownloader downloader = downloader();

    deliver(
        "wamid.IMG",
        "\"type\":\"image\",\"image\":{\"id\":\"m-png\",\"mime_type\":\"image/png\",\"caption\":\"exame\"}");
    deliver(
        "wamid.EXE",
        "\"type\":\"document\",\"document\":{\"id\":\"m-exe\",\"mime_type\":\"application/pdf\"}");
    deliver(
        "wamid.BIG",
        "\"type\":\"image\",\"image\":{\"id\":\"m-big\",\"mime_type\":\"image/jpeg\"}");
    deliver("wamid.TXT", "\"type\":\"text\",\"text\":{\"body\":\"só texto\"}");
    inbound.drain(channel);
    UUID conversation =
        UUID.fromString(json(owner.get(INBOX)).get("items").get(0).get("id").asText());
    UUID image = messageId(clinicA, "wamid.IMG");
    UUID exe = messageId(clinicA, "wamid.EXE");
    UUID text = messageId(clinicA, "wamid.TXT");
    String link = INBOX + "/" + conversation + "/messages/%s/media";

    // Registered with the message; not ready before the worker downloads it.
    assertThat(mediaStatus(owner, conversation, "exame")).isEqualTo("PENDING");
    assertThat(owner.get(link.formatted(image)).body()).contains("CONVERSATION_MEDIA_NOT_READY");

    // The worker downloads: accepted, wrong type and too large.
    assertThat(downloader.drain(channel)).isEqualTo(3);
    assertThat(
            scalar(
                clinicA,
                "SELECT string_agg(md.status || ':' || COALESCE(md.error_code, md.content_type),"
                    + " ',' ORDER BY m.wamid) FROM whatsapp_media md"
                    + " JOIN whatsapp_messages m ON m.id = md.message_id"))
        .isEqualTo("REJECTED:MEDIA_TOO_LARGE,REJECTED:TYPE_NOT_ALLOWED,STORED:image/png");
    assertThat(scalar(clinicA, "SELECT storage_key FROM whatsapp_media WHERE status = 'STORED'"))
        .startsWith("clinics/" + clinicA + "/media/");
    assertThat(mediaStatus(owner, conversation, "exame")).isEqualTo("STORED");

    // The link: temporary, an attachment with the detected type, and the file itself.
    JsonNode linked = json(viewer.get(link.formatted(image)));
    assertThat(linked.get("contentType").asText()).isEqualTo("image/png");
    String url = linked.get("url").asText();
    assertThat(url)
        .contains("response-content-disposition=attachment")
        .contains("X-Amz-Expires=300")
        .doesNotContain("synthetic-secret");
    HttpResponse<byte[]> file =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(file.body()).isEqualTo(PNG);
    assertThat(file.headers().firstValue("Content-Disposition"))
        .hasValueSatisfying(value -> assertThat(value).startsWith("attachment"));
    assertThat(file.headers().firstValue("Content-Type")).contains("image/png");

    // Refused, without media, and other clinics.
    assertThat(owner.get(link.formatted(exe)).body()).contains("CONVERSATION_MEDIA_REJECTED");
    assertThat(owner.get(link.formatted(text)).statusCode()).isEqualTo(404);
    assertThat(owner.get(link.formatted(UUID.randomUUID())).statusCode()).isEqualTo(404);
    assertThat(ownerB.get(link.formatted(image)).statusCode()).isEqualTo(404);
    assertThat(
            owner
                .get(INBOX + "/" + UUID.randomUUID() + "/messages/" + image + "/media")
                .statusCode())
        .isEqualTo(404);

    // Nothing left to download: the inbound signal is cleared.
    assertThat(downloader.drain(channel)).isZero();
    inbound.drain(channel);
    assertThat(
            jdbc.queryForObject(
                "SELECT inbound_pending_since IS NULL FROM whatsapp_channels WHERE id = ?",
                Boolean.class,
                channel))
        .isTrue();
  }

  private MediaDownloader downloader() {
    DriverManagerDataSource role =
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()), WHATSAPP, PASSWORD);
    JdbcTemplate worker = new JdbcTemplate(role);
    return new MediaDownloader(
        new TenantRegistryRepository(worker),
        new OutboundChannelRepository(worker),
        media,
        new ChannelUnsealer(FILES.resolve("keys/private").toString()),
        meta,
        storage,
        context.getBeanProvider(Clock.class));
  }

  private static String mediaStatus(Browser browser, UUID conversation, String body)
      throws Exception {
    for (JsonNode message :
        json(browser.get(INBOX + "/" + conversation + "/messages")).get("items")) {
      if (body.equals(message.path("body").asText())) {
        return message.get("mediaStatus").asText();
      }
    }
    throw new AssertionError("message not found");
  }

  private void deliver(String wamid, String content) {
    String body =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1001\",\"changes\":[{"
            + "\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"2001\"},"
            + "\"contacts\":[{\"wa_id\":\"5583999000001\",\"profile\":{\"name\":\"Sintético\"}}],"
            + "\"messages\":[{\"from\":\"5583999000001\",\"id\":\""
            + wamid
            + "\",\"timestamp\":\"1763380000\","
            + content
            + "}]}}]}]}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    webhook.receive(
        bytes, WebhookSignature.sign(APP_SECRET.getBytes(StandardCharsets.UTF_8), bytes));
  }

  private UUID messageId(UUID tenant, String wamid) throws SQLException {
    return UUID.fromString(
        scalar(tenant, "SELECT id FROM whatsapp_messages WHERE wamid = '" + wamid + "'"));
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

  private String scalar(UUID tenant, String sql) throws SQLException {
    try (Connection connection = clinicConnection(tenant);
        Statement statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
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

  /** A tiny PNG signature followed by synthetic bytes. */
  private static byte[] png() {
    byte[] bytes = new byte[2048];
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, bytes, 0, signature.length);
    for (int i = signature.length; i < bytes.length; i++) bytes[i] = (byte) i;
    return bytes;
  }

  /** A JPEG head over the 5 MB image limit. */
  private static byte[] big() {
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

  /** Graph media endpoints: the info with a URL on this host, and the bytes behind it. */
  static final class FakeGraph {
    private final Map<String, byte[]> files;
    private HttpServer server;

    FakeGraph(Map<String, byte[]> files) {
      this.files = files;
    }

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
      if (!"Bearer TOKEN-A".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        reply(exchange, 401, "{\"error\":{\"code\":190}}".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.startsWith("/files/")) {
        byte[] file = files.get(path.substring("/files/".length()));
        if (file == null) reply(exchange, 404, new byte[0]);
        else reply(exchange, 200, file);
        return;
      }
      String id = path.substring(path.lastIndexOf('/') + 1);
      if (!files.containsKey(id)) {
        reply(exchange, 400, "{\"error\":{\"code\":100}}".getBytes(StandardCharsets.UTF_8));
        return;
      }
      reply(
          exchange,
          200,
          ("{\"url\":\"" + url() + "/files/" + id + "\",\"mime_type\":\"image/png\"}")
              .getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length > 0) exchange.getResponseBody().write(body);
      exchange.close();
    }
  }
}
