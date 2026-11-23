package br.com.branlycrm.inbox;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Shared inbox over real HTTP (CRM-083): list and history with cursors, atomic take under a race,
 * release, transfer and the "needs a person" mark, roles and clinic isolation. Conversations are
 * written straight into the clinic database, as the worker does. Synthetic data only.
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
class InboxApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic inbox api secret 2026!";
  private static final String INBOX = "/v1/clinic/inbox/conversations";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired TenantProvisioningService provisioning;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", POSTGRES::getDatabaseName);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @AfterAll
  static void removeTheSecretDirectoryThisClassCreated() throws IOException {
    try (var files = Files.list(SECRETS)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(SECRETS);
  }

  @Test
  void listHistoryTakeTransferAndIsolation() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID ownerBId = account("owner-b@example.test");
    UUID clinicB = provision(ownerBId);
    UUID first = account("attendant-1@example.test");
    UUID second = account("attendant-2@example.test");
    UUID viewerId = account("viewer-a@example.test");
    member(clinicA, first, "ATTENDANT");
    member(clinicA, second, "ATTENDANT");
    member(clinicA, viewerId, "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser one = selected("attendant-1@example.test", clinicA);
    Browser two = selected("attendant-2@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    UUID older = conversation(clinicA, "Paciente Antigo", "+5583999000002", 3600);
    message(clinicA, older, "IN", "Bom dia", 3600);
    UUID recent = conversation(clinicA, "Paciente Recente", "+5583999000001", 10);
    message(clinicA, recent, "IN", "Olá, quero agendar", 30);
    message(clinicA, recent, "OUT", "Claro! Qual dia?", 20);
    message(clinicA, recent, "IN", "Sexta à tarde", 10);

    // List: most recent first, with a preview and "awaiting reply".
    JsonNode list = json(viewer.get(INBOX));
    assertThat(list.get("total").asLong()).isEqualTo(2);
    JsonNode top = list.get("items").get(0);
    assertThat(top.get("id").asText()).isEqualTo(recent.toString());
    assertThat(top.get("contactName").asText()).isEqualTo("Paciente Recente");
    assertThat(top.get("preview").asText()).isEqualTo("Sexta à tarde");
    assertThat(top.get("awaitingReply").asBoolean()).isTrue();
    assertThat(top.get("status").asText()).isEqualTo("WAITING");

    // History by cursor: the latest two, then the older one, then the poll of new ones.
    JsonNode latest = json(one.get(INBOX + "/" + recent + "/messages?limit=2"));
    assertThat(bodies(latest)).containsExactly("Claro! Qual dia?", "Sexta à tarde");
    JsonNode earlier =
        json(one.get(INBOX + "/" + recent + "/messages?before=" + latest.get("before").asText()));
    assertThat(bodies(earlier)).containsExactly("Olá, quero agendar");
    String after = latest.get("after").asText();
    JsonNode nothing = json(one.get(INBOX + "/" + recent + "/messages?after=" + after));
    assertThat(bodies(nothing)).isEmpty();
    assertThat(nothing.get("after").asText()).isEqualTo(after);
    message(clinicA, recent, "IN", "Pode ser às 15h?", 0);
    JsonNode fresh = json(one.get(INBOX + "/" + recent + "/messages?after=" + after));
    assertThat(bodies(fresh)).containsExactly("Pode ser às 15h?");
    // Stored later with the same second, or with an older Meta time (a retry): never skipped.
    String last = fresh.get("after").asText();
    message(clinicA, recent, "IN", "mesmo segundo", 0);
    message(clinicA, recent, "IN", "chegou atrasada", 600);
    assertThat(bodies(json(one.get(INBOX + "/" + recent + "/messages?after=" + last))))
        .containsExactly("mesmo segundo", "chegou atrasada");
    assertThat(
            one.get(INBOX + "/" + recent + "/messages?before=" + after + "&after=" + after)
                .statusCode())
        .isEqualTo(400);
    assertThat(one.get(INBOX + "/" + recent + "/messages?after=nonsense").statusCode())
        .isEqualTo(400);
    assertThat(one.get(INBOX + "?status=CLOSED").statusCode()).isEqualTo(400);

    // Two attendants take the same conversation at once: exactly one wins.
    List<Integer> statuses = race(one, two, INBOX + "/" + recent + "/take");
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    JsonNode taken = json(owner.get(INBOX + "/" + recent));
    UUID holder = UUID.fromString(taken.get("assigneeId").asText());
    assertThat(holder).isIn(first, second);
    Browser winner = holder.equals(first) ? one : two;
    Browser loser = holder.equals(first) ? two : one;
    assertThat(winner.post(INBOX + "/" + recent + "/take", true).statusCode()).isEqualTo(200);
    assertThat(loser.post(INBOX + "/" + recent + "/take", true).body())
        .contains("CONVERSATION_TAKEN");
    long version = taken.get("version").asLong();

    // Release: not someone else's (attendant), not with an old version.
    assertThat(release(loser, recent, version).body()).contains("CONVERSATION_NOT_YOURS");
    assertThat(release(winner, recent, version - 1).body()).contains("CONVERSATION_STALE");
    JsonNode released = json(release(winner, recent, version));
    assertThat(released.get("status").asText()).isEqualTo("WAITING");
    assertThat(released.get("assigneeId").isNull()).isTrue();

    // Transfer: owner only, to an active member who replies.
    String change = INBOX + "/" + recent + "/assignee";
    long now = released.get("version").asLong();
    assertThat(one.putJson(change, assignBody(now, second), true).statusCode()).isEqualTo(403);
    assertThat(owner.putJson(change, assignBody(now, viewerId), true).body())
        .contains("CONVERSATION_ASSIGNEE_INVALID");
    assertThat(owner.putJson(change, assignBody(now, UUID.randomUUID()), true).body())
        .contains("CONVERSATION_ASSIGNEE_INVALID");
    JsonNode assigned = json(owner.putJson(change, assignBody(now, second), true));
    assertThat(assigned.get("assigneeId").asText()).isEqualTo(second.toString());
    assertThat(assigned.get("status").asText()).isEqualTo("IN_SERVICE");

    // "Needs a person" and the filters.
    var marked =
        one.putJson(
            INBOX + "/" + recent + "/needs-human",
            "{\"version\":" + assigned.get("version").asLong() + ",\"needsHuman\":true}",
            true);
    assertThat(json(marked).get("needsHuman").asBoolean()).isTrue();
    assertThat(ids(json(one.get(INBOX + "?needsHuman=true")))).containsExactly(recent.toString());
    assertThat(ids(json(two.get(INBOX + "?assignee=mine")))).containsExactly(recent.toString());
    assertThat(ids(json(two.get(INBOX + "?assignee=none")))).containsExactly(older.toString());
    assertThat(ids(json(two.get(INBOX + "?status=IN_SERVICE")))).containsExactly(recent.toString());

    // Viewer reads, never acts.
    assertThat(viewer.get(INBOX + "/" + older).statusCode()).isEqualTo(200);
    assertThat(viewer.post(INBOX + "/" + older + "/take", true).statusCode()).isEqualTo(403);
    assertThat(
            viewer
                .putJson(
                    INBOX + "/" + older + "/needs-human",
                    "{\"version\":0,\"needsHuman\":true}",
                    true)
                .statusCode())
        .isEqualTo(403);

    // Clinic B sees nothing of A, by list, id, history or action.
    assertThat(json(ownerB.get(INBOX)).get("total").asLong()).isZero();
    assertThat(ownerB.get(INBOX + "/" + recent).statusCode()).isEqualTo(404);
    assertThat(ownerB.get(INBOX + "/" + recent + "/messages").statusCode()).isEqualTo(404);
    assertThat(ownerB.post(INBOX + "/" + older + "/take", true).statusCode()).isEqualTo(404);
    assertThat(release(ownerB, recent, 0).statusCode()).isEqualTo(404);
    assertThat(
            ownerB
                .putJson(INBOX + "/" + recent + "/assignee", assignBody(0, ownerBId), true)
                .statusCode())
        .isEqualTo(404);
    assertThat(
            ownerB
                .putJson(
                    INBOX + "/" + recent + "/needs-human",
                    "{\"version\":0,\"needsHuman\":true}",
                    true)
                .statusCode())
        .isEqualTo(404);

    // An archived contact keeps its conversation, flagged.
    clinic(
        clinicA,
        "UPDATE contacts SET archived_at = now(), archived_by = gen_random_uuid()"
            + " WHERE name = 'Paciente Antigo'");
    assertThat(json(owner.get(INBOX + "/" + older)).get("contactArchived").asBoolean()).isTrue();
    assertThat(json(owner.get(INBOX + "/" + older)).get("status").asText()).isEqualTo("WAITING");

    // Every change left an event, with its author.
    assertThat(
            scalar(
                clinicA,
                "SELECT string_agg(type, ',' ORDER BY seq) FROM whatsapp_conversation_events"))
        .isEqualTo("TAKEN,RELEASED,ASSIGNED,NEEDS_HUMAN");
  }

  private List<Integer> race(Browser one, Browser two, String path) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> answers = new ArrayList<>();
      for (Browser browser : new Browser[] {one, two}) {
        Callable<Integer> call =
            () -> {
              start.await();
              return browser.post(path, true).statusCode();
            };
        answers.add(pool.submit(call));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> answer : answers) statuses.add(answer.get());
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  private static HttpResponse<String> release(Browser browser, UUID conversation, long version)
      throws Exception {
    return browser.postJson(
        INBOX + "/" + conversation + "/release", "{\"version\":" + version + "}", true);
  }

  private static String assignBody(long version, UUID assignee) {
    return "{\"version\":" + version + ",\"assigneeId\":\"" + assignee + "\"}";
  }

  private static List<String> bodies(JsonNode page) {
    List<String> bodies = new ArrayList<>();
    page.get("items").forEach(item -> bodies.add(item.get("body").asText()));
    return bodies;
  }

  private static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return Browser.JSON.readTree(response.body());
  }

  /** A WhatsApp contact and its conversation, last active {@code secondsAgo}. */
  private UUID conversation(UUID tenant, String name, String phone, int secondsAgo)
      throws SQLException {
    UUID contact = UUID.randomUUID();
    UUID conversation = UUID.randomUUID();
    clinic(
        tenant,
        "INSERT INTO contacts (id, name, name_search, phone_e164, created_via, created_at,"
            + " updated_at) VALUES ('"
            + contact
            + "', '"
            + name
            + "', lower('"
            + name
            + "'), '"
            + phone
            + "', 'WHATSAPP', now(), now())");
    clinic(
        tenant,
        "INSERT INTO whatsapp_conversations (id, contact_id, channel_id, created_at,"
            + " last_message_at, last_inbound_at) VALUES ('"
            + conversation
            + "', '"
            + contact
            + "', '"
            + UUID.randomUUID()
            + "', now(), now() - interval '"
            + secondsAgo
            + " seconds', now() - interval '"
            + secondsAgo
            + " seconds')");
    return conversation;
  }

  private void message(
      UUID tenant, UUID conversation, String direction, String body, int secondsAgo)
      throws SQLException {
    clinic(
        tenant,
        "INSERT INTO whatsapp_messages (id, conversation_id, wamid, direction, message_type, body,"
            + " sent_at, created_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + conversation
            + "', 'wamid."
            + UUID.randomUUID()
            + "', '"
            + direction
            + "', 'text', '"
            + body
            + "', now() - interval '"
            + secondsAgo
            + " seconds', now())");
    clinic(
        tenant,
        "UPDATE whatsapp_conversations SET last_message_at = GREATEST(last_message_at,"
            + " now() - interval '"
            + secondsAgo
            + " seconds') WHERE id = '"
            + conversation
            + "'");
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

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-inbox-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
