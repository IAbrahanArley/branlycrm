package br.com.branlycrm.whatsapp;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.whatsapp.domain.WebhookSignature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * WhatsApp webhook over real HTTP (CRM-082a, ADR-036): verification, signature before anything
 * else, routing through the central registry with clinic isolation, deduplication, the pending
 * signal and 5xx on storage failures. Synthetic identifiers and texts only.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S",
      MfaFixture.KEY_PROPERTY
    })
class WhatsappWebhookIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory("branly-webhook-it-");
  private static final Path WEBHOOK = temporaryDirectory("branly-webhook-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";
  private static final String VERIFY_TOKEN = "synthetic-verify-token-2026";
  private static final String PATH = "/v1/webhooks/whatsapp";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired TenantProvisioningService provisioning;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    Files.writeString(WEBHOOK.resolve("app-secret"), APP_SECRET + "\n");
    Files.writeString(WEBHOOK.resolve("verify-token"), VERIFY_TOKEN);
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", POSTGRES::getDatabaseName);
    registry.add(
        "app.whatsapp.webhook.app-secret-file", () -> WEBHOOK.resolve("app-secret").toString());
    registry.add(
        "app.whatsapp.webhook.verify-token-file", () -> WEBHOOK.resolve("verify-token").toString());
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @AfterAll
  static void removeTheDirectoriesThisClassCreated() throws IOException {
    for (Path directory : new Path[] {SECRETS, WEBHOOK}) {
      try (Stream<Path> files = Files.walk(directory)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }

  @Test
  void verifiesSignsRoutesAndStoresPerClinic() throws Exception {
    UUID clinicA = provision();
    UUID clinicB = provision();
    UUID channelA = channel(clinicA, "ACTIVE", "1001", "2001");
    UUID channelB = channel(clinicB, "ACTIVE", "1002", "2002");
    channel(provision(), "PENDING", null, "2003");

    // Verification handshake: only the right token echoes the challenge.
    var verified =
        get("?hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=8136");
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(verified.body()).isEqualTo("8136");
    assertThat(verified.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
    assertThat(get("?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=1").statusCode())
        .isEqualTo(403);
    assertThat(
            get("?hub.mode=subscribe&hub.verify_token=" + VERIFY_TOKEN + "&hub.challenge=%3Cx%3E")
                .statusCode())
        .isEqualTo(403);

    // Without a valid signature nothing is stored, whatever the body says.
    String delivery =
        delivery(entry("1001", "2001", "wamid.A1"), entry("1002", "2002", "wamid.B1"));
    assertThat(post(delivery, null).statusCode()).isEqualTo(401);
    assertThat(post(delivery, "sha256=" + "0".repeat(64)).statusCode()).isEqualTo(401);
    assertThat(post(delivery, sign(delivery.replace("A1", "A9"))).statusCode()).isEqualTo(401);
    assertThat(count(clinicA)).isZero();
    assertThat(count(clinicB)).isZero();

    // Signed: each entry goes to its own clinic, no session, cookie nor CSRF involved.
    var stored = post(delivery, sign(delivery));
    assertThat(stored.statusCode()).isEqualTo(200);
    assertThat(stored.headers().firstValue("Set-Cookie")).isEmpty();
    assertThat(channelsOf(clinicA)).containsExactly(channelA.toString());
    assertThat(channelsOf(clinicB)).containsExactly(channelB.toString());
    assertThat(pendingSince(channelA)).isTrue();
    assertThat(pendingSince(channelB)).isTrue();

    // A redelivery stores nothing new.
    assertThat(post(delivery, sign(delivery)).statusCode()).isEqualTo(200);
    assertThat(count(clinicA)).isEqualTo(1);
    assertThat(count(clinicB)).isEqualTo(1);

    // B's number inside A's account, a pending channel and an unknown number: acknowledged and
    // discarded, never written anywhere.
    String foreign =
        delivery(
            entry("1001", "2002", "wamid.X1"),
            entry("1003", "2003", "wamid.X2"),
            entry("1009", "2999", "wamid.X3"));
    assertThat(post(foreign, sign(foreign)).statusCode()).isEqualTo(200);
    assertThat(count(clinicA)).isEqualTo(1);
    assertThat(count(clinicB)).isEqualTo(1);

    // Account events go by the account id to the live channel of that account only.
    String account =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1002\",\"changes\":["
            + "{\"field\":\"account_update\",\"value\":{\"event\":\"VERIFIED_ACCOUNT\"}}]}]}";
    assertThat(post(account, sign(account)).statusCode()).isEqualTo(200);
    assertThat(count(clinicA)).isEqualTo(1);
    assertThat(count(clinicB)).isEqualTo(2);

    // A clinic that refuses the write fails the whole delivery with 5xx; the retry is safe.
    String runtimeB = databaseUser(clinicB);
    clinic(clinicB, "REVOKE INSERT ON whatsapp_inbound_events FROM " + runtimeB);
    String retry = delivery(entry("1001", "2001", "wamid.A2"), entry("1002", "2002", "wamid.B2"));
    assertThat(post(retry, sign(retry)).statusCode()).isEqualTo(503);
    clinic(clinicB, "GRANT INSERT ON whatsapp_inbound_events TO " + runtimeB);
    assertThat(post(retry, sign(retry)).statusCode()).isEqualTo(200);
    assertThat(count(clinicA)).isEqualTo(2);
    assertThat(count(clinicB)).isEqualTo(3);

    // An account shared by two clinics: only an event naming one number reaches its clinic.
    UUID clinicC = provision();
    UUID clinicD = provision();
    channel(clinicC, "ACTIVE", "1005", "2005", "+55 11 90000-0005");
    channel(clinicD, "ACTIVE", "1005", "2006", "+55 11 90000-0006");
    String shared =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1005\","
            + "\"time\":1763380100,\"changes\":["
            + "{\"field\":\"phone_number_quality_update\",\"value\":{"
            + "\"display_phone_number\":\"5511900000005\",\"event\":\"FLAGGED\"}},"
            + "{\"field\":\"account_update\",\"value\":{\"event\":\"VERIFIED_ACCOUNT\"}}]}]}";
    assertThat(post(shared, sign(shared)).statusCode()).isEqualTo(200);
    assertThat(count(clinicC)).isEqualTo(1);
    assertThat(count(clinicD)).isZero();

    // A failed channel and a suspended clinic: acknowledged, nothing written.
    UUID clinicE = provision();
    UUID failed = channel(clinicE, "ACTIVE", "1007", "2007", null);
    jdbc.update(
        "UPDATE whatsapp_channels SET status = 'FAILED', failure_code = 'TOKEN_REVOKED'"
            + " WHERE id = ?",
        failed);
    UUID clinicF = provision();
    channel(clinicF, "ACTIVE", "1008", "2008", null);
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", clinicF);
    String inactive =
        delivery(entry("1007", "2007", "wamid.E1"), entry("1008", "2008", "wamid.F1"));
    assertThat(post(inactive, sign(inactive)).statusCode()).isEqualTo(200);
    assertThat(count(clinicE)).isZero();
    jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", clinicF);
    assertThat(count(clinicF)).isZero();

    // A NUL character, which jsonb refuses, does not block the delivery forever.
    String nul = delivery(entry("1001", "2001", "wamid.A3").replace("Olá", "a\\u0000b"));
    assertThat(post(nul, sign(nul)).statusCode()).isEqualTo(200);
    assertThat(count(clinicA)).isEqualTo(3);

    // Other methods are refused with the standard body.
    var put =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(put.statusCode()).isEqualTo(403);
    assertThat(put.body()).contains("ACCESS_DENIED");

    // Signed but not JSON.
    assertThat(post("not json", sign("not json")).statusCode()).isEqualTo(400);

    // Above 3 MiB: refused before reading (the server may also close the connection).
    String huge = "x".repeat(3 * 1024 * 1024 + 1);
    try {
      assertThat(post(huge, sign(huge)).statusCode()).isEqualTo(413);
    } catch (IOException closed) {
      // Acceptable: nothing was stored either way.
    }
    assertThat(count(clinicA)).isEqualTo(3);
  }

  private HttpResponse<String> get(String query) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + query)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String body, String signature)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (signature != null) request.header("X-Hub-Signature-256", signature);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String sign(String body) {
    return WebhookSignature.sign(
        APP_SECRET.getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
  }

  private static String delivery(String... entries) {
    return "{\"object\":\"whatsapp_business_account\",\"entry\":["
        + String.join(",", entries)
        + "]}";
  }

  private static String entry(String waba, String phone, String wamid) {
    return "{\"id\":\""
        + waba
        + "\",\"changes\":[{\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\","
        + "\"metadata\":{\"display_phone_number\":\"5500000000000\",\"phone_number_id\":\""
        + phone
        + "\"},\"contacts\":[{\"wa_id\":\"5500000000001\",\"profile\":{\"name\":\"Sintético\"}}],"
        + "\"messages\":[{\"from\":\"5500000000001\",\"id\":\""
        + wamid
        + "\",\"timestamp\":\"1763380000\",\"type\":\"text\",\"text\":{\"body\":\"Olá\"}}]}}]}";
  }

  private UUID channel(UUID tenant, String status, String waba, String phone) {
    return channel(tenant, status, waba, phone, null);
  }

  private UUID channel(UUID tenant, String status, String waba, String phone, String display) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO whatsapp_channels (id, tenant_id, status, waba_id, phone_number_id,"
            + " display_phone_number, sealed_token, key_version, connected_by, created_at,"
            + " updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, decode('01', 'hex'), 1, ?, now(), now())",
        id,
        tenant,
        status,
        waba,
        phone,
        display,
        UUID.randomUUID());
    return id;
  }

  private boolean pendingSince(UUID channel) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT inbound_pending_since IS NOT NULL FROM whatsapp_channels WHERE id = ?",
            Boolean.class,
            channel));
  }

  private int count(UUID tenant) throws SQLException {
    return channelsOf(tenant).size();
  }

  private java.util.List<String> channelsOf(UUID tenant) throws SQLException {
    java.util.List<String> channels = new java.util.ArrayList<>();
    try (Connection connection = clinicConnection(tenant);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT channel_id FROM whatsapp_inbound_events ORDER BY received_at, id")) {
      while (rows.next()) channels.add(rows.getString(1));
    }
    return channels;
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

  private String databaseUser(UUID tenant) {
    return jdbc.queryForObject(
        "SELECT database_user FROM tenants WHERE tenant_id = ?", String.class, tenant);
  }

  private UUID provision() {
    UUID owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        owner,
        owner + "@example.test");
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

  private static Path temporaryDirectory(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
