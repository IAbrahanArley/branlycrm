package br.com.branlycrm.whatsapp;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.whatsapp.domain.WebhookSignature;
import br.com.branlycrm.whatsapp.services.InboundProcessingService;
import br.com.branlycrm.whatsapp.services.WhatsappWebhookService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Inbound worker (CRM-082b, CRM-091, ADR-036), from the signed webhook to the clinic's contact,
 * lead, origin and message, with real clinic databases and their runtime roles. The drain is called
 * directly, as the worker loop does after a notification. Synthetic numbers and texts only.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S"
    })
class WhatsappWorkerIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory("branly-worker-it-");
  private static final Path WEBHOOK = temporaryDirectory("branly-worker-files-");
  private static final String APP_SECRET = "synthetic-app-secret-0123456789";

  @Autowired JdbcTemplate jdbc;
  @Autowired TenantProvisioningService provisioning;
  @Autowired WhatsappWebhookService webhook;
  @Autowired InboundProcessingService inbound;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    Files.writeString(WEBHOOK.resolve("app-secret"), APP_SECRET);
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", POSTGRES::getDatabaseName);
    registry.add(
        "app.whatsapp.webhook.app-secret-file", () -> WEBHOOK.resolve("app-secret").toString());
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
  void messagesBecomeContactsLeadsOriginsAndConversations() throws Exception {
    UUID clinicA = provision();
    UUID clinicB = provision();
    UUID channelA = channel(clinicA, "1001", "2001");
    UUID channelB = channel(clinicB, "1002", "2002");

    // An ad click from an old 8-digit mobile: contact, lead in New with the ad origin, message.
    long started = System.nanoTime();
    deliver(
        message(
            "1001",
            "2001",
            "558399001234",
            "wamid.A1",
            "\"type\":\"text\",\"text\":{\"body\":\"Quero saber do clareamento\"},"
                + "\"referral\":{\"source_type\":\"ad\",\"source_id\":\"120200000000001\","
                + "\"headline\":\"Clareamento\",\"ctwa_clid\":\"clid-synthetic\"}",
            "Paciente Sintético"));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(5));

    assertThat(rows(clinicA, "SELECT name, phone_e164, created_via, created_by FROM contacts"))
        .containsExactly("Paciente Sintético|+5583999001234|WHATSAPP|null");
    assertThat(
            rows(
                clinicA,
                "SELECT l.status, s.system_key, l.origin_type, l.origin_source, l.origin_ad_id,"
                    + " l.origin_title, l.origin_ctwa_clid, l.created_by FROM leads l"
                    + " JOIN pipeline_stages s ON s.id = l.stage_id"))
        .containsExactly(
            "OPEN|NEW|PAID_TRAFFIC|AD|120200000000001|Clareamento|clid-synthetic|null");
    assertThat(rows(clinicA, "SELECT type, actor_id FROM lead_events"))
        .containsExactly("CREATED|null");
    assertThat(
            rows(
                clinicA,
                "SELECT direction, sent_by_app, message_type, body FROM whatsapp_messages"))
        .containsExactly("IN|f|text|Quero saber do clareamento");
    assertThat(
            rows(
                clinicA,
                "SELECT channel_id, last_inbound_at IS NOT NULL FROM whatsapp_conversations"))
        .containsExactly(channelA + "|t");
    assertThat(rows(clinicA, "SELECT processed_at IS NOT NULL FROM whatsapp_inbound_events"))
        .containsExactly("t");
    assertThat(pending(channelA)).isFalse();

    // The same delivery again: nothing new anywhere.
    deliver(
        message(
            "1001",
            "2001",
            "558399001234",
            "wamid.A1",
            "\"type\":\"text\",\"text\":{\"body\":\"Quero saber do clareamento\"}",
            "Paciente Sintético"));
    assertThat(inbound.drain(channelA).processed()).isZero();

    // Same person with the ninth digit and a code: same contact, same lead, one touch (RN-02).
    deliver(
        message(
            "1001",
            "2001",
            "5583999001234",
            "wamid.A2",
            "\"type\":\"text\",\"text\":{\"body\":\"[IG01] voltei\"}",
            "Outro Nome"));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(count(clinicA, "contacts")).isEqualTo(1);
    assertThat(rows(clinicA, "SELECT name FROM contacts")).containsExactly("Paciente Sintético");
    assertThat(count(clinicA, "leads")).isEqualTo(1);
    assertThat(rows(clinicA, "SELECT origin_type FROM leads")).containsExactly("PAID_TRAFFIC");
    assertThat(rows(clinicA, "SELECT origin_source, code FROM origin_touches"))
        .containsExactly("CODE|IG01");
    assertThat(count(clinicA, "whatsapp_messages")).isEqualTo(2);
    assertThat(count(clinicA, "whatsapp_conversations")).isEqualTo(1);

    // After the lead closes, the next message opens a new one (RN-01).
    clinic(
        clinicA,
        "UPDATE leads SET status = 'LOST', closed_at = now(),"
            + " stage_id = (SELECT id FROM pipeline_stages WHERE system_key = 'LOST'),"
            + " loss_reason_id = (SELECT id FROM loss_reasons ORDER BY id LIMIT 1)");
    deliver(
        message(
            "1001",
            "2001",
            "5583999001234",
            "wamid.A3",
            "\"type\":\"text\",\"text\":{\"body\":\"oi de novo\"}",
            null));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(rows(clinicA, "SELECT status || ':' || origin_type FROM leads ORDER BY opened_at"))
        .containsExactly("LOST:PAID_TRAFFIC", "OPEN:UNIDENTIFIED");

    // An echo of the WhatsApp Business app: contact and outbound message, never a lead.
    deliver(
        delivery(
            "1001",
            "smb_message_echoes",
            "{\"metadata\":{\"phone_number_id\":\"2001\"},\"message_echoes\":[{\"from\":\"2001\","
                + "\"to\":\"5511988887777\",\"id\":\"wamid.A4\",\"timestamp\":\"1763380000\","
                + "\"type\":\"text\",\"text\":{\"body\":\"Olá, aqui é a clínica\"}}]}"));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(count(clinicA, "contacts")).isEqualTo(2);
    assertThat(count(clinicA, "leads")).isEqualTo(2);
    assertThat(
            rows(
                clinicA,
                "SELECT direction, sent_by_app FROM whatsapp_messages WHERE wamid ="
                    + " 'wamid.A4'"))
        .containsExactly("OUT|t");

    // A status is processed without writing anything (sending comes with CRM-084).
    deliver(
        delivery(
            "1001",
            "messages",
            "{\"metadata\":{\"phone_number_id\":\"2001\"},\"statuses\":[{\"id\":\"wamid.X\","
                + "\"status\":\"delivered\",\"timestamp\":\"1763380001\"}]}"));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(count(clinicA, "whatsapp_messages")).isEqualTo(4);

    // Clinic B: its own contact; A is untouched.
    deliver(
        message(
            "1002",
            "2002",
            "5583999001234",
            "wamid.B1",
            "\"type\":\"text\",\"text\":{\"body\":\"oi B\"}",
            "Pessoa B"));
    assertThat(inbound.drain(channelB).processed()).isEqualTo(1);
    assertThat(rows(clinicB, "SELECT name FROM contacts")).containsExactly("Pessoa B");
    assertThat(count(clinicB, "whatsapp_messages")).isEqualTo(1);
    assertThat(count(clinicA, "contacts")).isEqualTo(2);
    assertThat(count(clinicA, "whatsapp_messages")).isEqualTo(4);

    // A contact first seen in an echo, named by its number, takes its own profile name.
    assertThat(rows(clinicA, "SELECT name FROM contacts WHERE phone_e164 = '+5511988887777'"))
        .containsExactly("+5511988887777");
    deliver(
        message(
            "1001",
            "2001",
            "5511988887777",
            "wamid.A6",
            "\"type\":\"text\",\"text\":{\"body\":\"obrigado\"}",
            "Paciente Eco"));
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(rows(clinicA, "SELECT name FROM contacts WHERE phone_e164 = '+5511988887777'"))
        .containsExactly("Paciente Eco");

    // Per-contact order: while an earlier event of the contact waits for a retry, a later one
    // waits too, so the ad click keeps the lead's origin.
    deliver(
        message(
            "1001",
            "2001",
            "5521977776666",
            "wamid.C1",
            "\"type\":\"text\",\"text\":{\"body\":\"anuncio\"},"
                + "\"referral\":{\"source_type\":\"ad\",\"source_id\":\"ad-2\"}",
            "Paciente Ordem"));
    clinic(
        clinicA,
        "UPDATE whatsapp_inbound_events SET attempts = 1, next_attempt_at = now() + interval"
            + " '1 hour' WHERE processed_at IS NULL");
    deliver(
        message(
            "1001",
            "2001",
            "5521977776666",
            "wamid.C2",
            "\"type\":\"text\",\"text\":{\"body\":\"oi\"}",
            "Paciente Ordem"));
    assertThat(inbound.drain(channelA).processed()).isZero();
    clinic(clinicA, "UPDATE whatsapp_inbound_events SET next_attempt_at = now()");
    assertThat(inbound.drain(channelA).processed()).isEqualTo(2);
    assertThat(
            rows(
                clinicA,
                "SELECT l.origin_type || ':' || l.origin_ad_id FROM leads l JOIN contacts c"
                    + " ON c.id = l.contact_id WHERE c.phone_e164 = '+5521977776666'"))
        .containsExactly("PAID_TRAFFIC:ad-2");

    // A clinic failure (here, a missing grant) delays the event without spending an attempt; the
    // signal stays raised until it succeeds, and nothing of the event was kept.
    String runtimeA = databaseUser(clinicA);
    clinic(clinicA, "REVOKE INSERT ON whatsapp_messages FROM " + runtimeA);
    deliver(
        message(
            "1001",
            "2001",
            "5583999001234",
            "wamid.A5",
            "\"type\":\"text\",\"text\":{\"body\":\"falha\"}",
            null));
    InboundProcessingService.Pass failed = inbound.drain(channelA);
    assertThat(failed.failed()).isEqualTo(1);
    assertThat(
            rows(
                clinicA,
                "SELECT attempts, last_error IS NOT NULL, next_attempt_at > now(),"
                    + " processed_at IS NULL FROM whatsapp_inbound_events"
                    + " WHERE processed_at IS NULL"))
        .containsExactly("0|t|t|t");
    assertThat(count(clinicA, "contacts")).isEqualTo(3);
    assertThat(pending(channelA)).isTrue();
    assertThat(inbound.drain(channelA).processed()).isZero();
    clinic(clinicA, "GRANT INSERT ON whatsapp_messages TO " + runtimeA);
    clinic(clinicA, "UPDATE whatsapp_inbound_events SET next_attempt_at = now()");
    assertThat(inbound.drain(channelA).processed()).isEqualTo(1);
    assertThat(count(clinicA, "whatsapp_messages")).isEqualTo(8);
    assertThat(pending(channelA)).isFalse();

    // Retention: the raw JSON of events processed over 30 days ago is cleared, the row stays.
    clinic(
        clinicA,
        "UPDATE whatsapp_inbound_events SET processed_at = now() - interval '31 days'"
            + " WHERE dedup_key = (SELECT min(dedup_key) FROM whatsapp_inbound_events)");
    assertThat(inbound.applyRetention()).isEqualTo(1);
    assertThat(count(clinicA, "whatsapp_inbound_events WHERE payload IS NULL")).isEqualTo(1);
  }

  private void deliver(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    webhook.receive(
        bytes, WebhookSignature.sign(APP_SECRET.getBytes(StandardCharsets.UTF_8), bytes));
  }

  private static String message(
      String waba, String phone, String from, String wamid, String content, String name) {
    String contacts =
        name == null
            ? ""
            : "\"contacts\":[{\"wa_id\":\""
                + from
                + "\",\"profile\":{\"name\":\""
                + name
                + "\"}}],";
    return delivery(
        waba,
        "messages",
        "{\"messaging_product\":\"whatsapp\",\"metadata\":{\"phone_number_id\":\""
            + phone
            + "\"},"
            + contacts
            + "\"messages\":[{\"from\":\""
            + from
            + "\",\"id\":\""
            + wamid
            + "\",\"timestamp\":\"1763380000\","
            + content
            + "}]}");
  }

  private static String delivery(String waba, String field, String value) {
    return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\""
        + waba
        + "\",\"changes\":[{\"field\":\""
        + field
        + "\",\"value\":"
        + value
        + "}]}]}";
  }

  private UUID channel(UUID tenant, String waba, String phone) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO whatsapp_channels (id, tenant_id, status, waba_id, phone_number_id,"
            + " sealed_token, key_version, connected_by, created_at, updated_at)"
            + " VALUES (?, ?, 'ACTIVE', ?, ?, decode('01', 'hex'), 1, ?, now(), now())",
        id,
        tenant,
        waba,
        phone,
        UUID.randomUUID());
    return id;
  }

  private boolean pending(UUID channel) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT inbound_pending_since IS NOT NULL FROM whatsapp_channels WHERE id = ?",
            Boolean.class,
            channel));
  }

  private int count(UUID tenant, String table) throws SQLException {
    return Integer.parseInt(rows(tenant, "SELECT count(*) FROM " + table).getFirst());
  }

  /** Each row as its columns joined by {@code |}, nulls as "null". */
  private List<String> rows(UUID tenant, String sql) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection connection = clinicConnection(tenant);
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      int columns = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> values = new ArrayList<>();
        for (int column = 1; column <= columns; column++) {
          values.add(String.valueOf(result.getString(column)));
        }
        rows.add(String.join("|", values));
      }
    }
    return rows;
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
