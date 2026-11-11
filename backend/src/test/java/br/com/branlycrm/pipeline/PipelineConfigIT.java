package br.com.branlycrm.pipeline;

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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Funnel and loss reason configuration over real HTTP (CRM-036): stage creation, renaming,
 * reordering and archiving with the funnel version, the archive versus lead move race, reason
 * rules, roles and clinic isolation. One test, so each browser logs in once.
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
class PipelineConfigIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic funnel api secret 2026!";
  private static final String LEADS = "/v1/clinic/leads";

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
  void funnelAndLossReasonConfiguration() throws Exception {
    UUID clinicA = provision(account("owner-a@example.test"));
    UUID clinicB = provision(account("owner-b@example.test"));
    member(clinicA, account("attendant-a@example.test"), "ATTENDANT");
    member(clinicA, account("viewer-a@example.test"), "VIEWER");
    Browser owner = selected("owner-a@example.test", clinicA);
    Browser attendant = selected("attendant-a@example.test", clinicA);
    Browser viewer = selected("viewer-a@example.test", clinicA);
    Browser ownerB = selected("owner-b@example.test", clinicB);

    JsonNode funnel = funnel(owner);
    assertThat(funnel.get("version").asLong()).isZero();
    String entry = stageId(funnel, "NEW");
    String won = stageId(funnel, "WON");

    // Create at the end; duplicates and stale versions are refused.
    var created = owner.postJson(STAGES, name("Avaliação", 0), true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    funnel = json(created);
    String evaluation = byName(funnel, "Avaliação");
    assertThat(position(funnel, evaluation)).isEqualTo(5);
    assertThat(code(owner.postJson(STAGES, name("NOVO", 1), true))).isEqualTo("STAGE_DUPLICATE");
    assertThat(code(owner.postJson(STAGES, name("Outra", 0), true))).isEqualTo("PIPELINE_STALE");

    // Rename the won stage; reorder with the whole list only.
    funnel = ok(owner.putJson(STAGES + "/" + won, name("Fechou", version(funnel)), true));
    assertThat(funnel.toString()).contains("Fechou");
    List<String> open = openIds(funnel);
    List<String> reversed = new ArrayList<>(open);
    Collections.reverse(reversed);
    assertThat(
            code(
                owner.putJson(
                    STAGES + "/order", order(reversed.subList(0, 3), version(funnel)), true)))
        .isEqualTo("STAGE_ORDER_MISMATCH");
    funnel = ok(owner.putJson(STAGES + "/order", order(reversed, version(funnel)), true));
    assertThat(openIds(funnel)).isEqualTo(reversed);
    assertPositions(funnel);

    // Protected stages; a stage with an open lead is not archived.
    assertThat(code(owner.putJson(archive(entry), archived(true, version(funnel)), true)))
        .isEqualTo("STAGE_PROTECTED");
    assertThat(code(owner.putJson(archive(won), archived(true, version(funnel)), true)))
        .isEqualTo("STAGE_PROTECTED");
    String lead = lead(owner, contact(owner, "(11) 98500-0001"));
    assertThat(
            owner.putJson(LEADS + "/" + lead + "/stage", stage(evaluation, 0), true).statusCode())
        .isEqualTo(200);
    assertThat(code(owner.putJson(archive(evaluation), archived(true, version(funnel)), true)))
        .isEqualTo("STAGE_HAS_OPEN_LEADS");
    assertThat(owner.putJson(LEADS + "/" + lead + "/stage", stage(entry, 1), true).statusCode())
        .isEqualTo(200);
    funnel = ok(owner.putJson(archive(evaluation), archived(true, version(funnel)), true));
    assertThat(openIds(funnel)).doesNotContain(evaluation).hasSize(4);
    assertPositions(funnel);
    assertThat(funnel.get("archivedStages").get(0).get("id").asText()).isEqualTo(evaluation);
    assertThat(code(owner.putJson(LEADS + "/" + lead + "/stage", stage(evaluation, 2), true)))
        .isEqualTo("LEAD_STAGE_NOT_ALLOWED");
    funnel = ok(owner.putJson(archive(evaluation), archived(false, version(funnel)), true));
    assertThat(position(funnel, evaluation)).isEqualTo(5);
    long unchanged = version(funnel);
    funnel = ok(owner.putJson(archive(evaluation), archived(false, unchanged), true));
    assertThat(version(funnel)).as("idempotent unarchive keeps the version").isEqualTo(unchanged);

    // Two reorders at the same version: one wins, positions stay 1..n.
    List<String> current = openIds(funnel);
    List<String> rotated = new ArrayList<>(current);
    Collections.rotate(rotated, 1);
    long at = version(funnel);
    List<Integer> reorders =
        race(
            () -> owner.putJson(STAGES + "/order", order(rotated, at), true).statusCode(),
            () -> owner.putJson(STAGES + "/order", order(current, at), true).statusCode());
    assertThat(reorders).containsExactlyInAnyOrder(200, 409);
    funnel = funnel(owner);
    assertPositions(funnel);

    // Archiving races moving a lead into the stage: never an open lead in an archived stage.
    String racing = lead(owner, contact(owner, "(11) 98500-0002"));
    long funnelVersion = version(funnel);
    List<Integer> outcomes =
        race(
            () ->
                owner
                    .putJson(archive(evaluation), archived(true, funnelVersion), true)
                    .statusCode(),
            () ->
                attendant
                    .putJson(LEADS + "/" + racing + "/stage", stage(evaluation, 0), true)
                    .statusCode());
    assertThat(outcomes).containsExactlyInAnyOrder(200, 409);
    JsonNode racingLead = json(owner.get(LEADS + "/" + racing));
    boolean archivedNow = funnel(owner).get("archivedStages").toString().contains(evaluation);
    assertThat(archivedNow && evaluation.equals(racingLead.get("stageId").asText())).isFalse();

    // Loss reasons: create, rename at the version read, never zero active.
    var reason = owner.postJson(REASONS, name("Mudou de cidade", 0), true);
    assertThat(reason.statusCode()).as(reason.body()).isEqualTo(201);
    String moved = json(reason).get("id").asText();
    assertThat(code(owner.postJson(REASONS, name("PREÇO", 0), true)))
        .isEqualTo("LOSS_REASON_DUPLICATE");
    var renamed = owner.putJson(REASONS + "/" + moved, name("Mudou de bairro", 0), true);
    assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
    assertThat(json(renamed).get("version").asLong()).isOne();
    assertThat(code(owner.putJson(REASONS + "/" + moved, name("Outro nome", 0), true)))
        .isEqualTo("LOSS_REASON_STALE");
    JsonNode reasons = json(owner.get("/v1/clinic/loss-reasons"));
    for (int i = 0; i < reasons.size() - 1; i++) {
      String id = reasons.get(i).get("id").asText();
      assertThat(
              owner.putJson(REASONS + "/" + id + "/archived", archived(true, 0), true).statusCode())
          .isEqualTo(200);
    }
    String last = reasons.get(reasons.size() - 1).get("id").asText();
    assertThat(code(owner.putJson(REASONS + "/" + last + "/archived", archived(true, 0), true)))
        .isEqualTo("LOSS_REASON_LAST_ACTIVE");
    assertThat(json(owner.get("/v1/clinic/loss-reasons"))).hasSize(1);

    // Roles: only owner and manager configure.
    for (Browser denied : List.of(attendant, viewer)) {
      assertThat(code(denied.postJson(STAGES, name("Negada", version(funnel(owner))), true)))
          .isEqualTo("CLINIC_ACCESS_DENIED");
      assertThat(code(denied.postJson(REASONS, name("Negado", 0), true)))
          .isEqualTo("CLINIC_ACCESS_DENIED");
    }

    // Clinic B cannot touch A's stages nor reasons, and its funnel is untouched.
    JsonNode funnelB = funnel(ownerB);
    assertThat(funnelB.get("version").asLong()).isZero();
    assertThat(code(ownerB.putJson(STAGES + "/" + evaluation, name("Invasão", 0), true)))
        .isEqualTo("STAGE_NOT_FOUND");
    assertThat(code(ownerB.putJson(archive(evaluation), archived(true, 0), true)))
        .isEqualTo("STAGE_NOT_FOUND");
    assertThat(code(ownerB.putJson(REASONS + "/" + moved, name("Invasão", 1), true)))
        .isEqualTo("LOSS_REASON_NOT_FOUND");
    assertThat(code(ownerB.putJson(REASONS + "/" + moved + "/archived", archived(true, 0), true)))
        .isEqualTo("LOSS_REASON_NOT_FOUND");
    assertThat(funnel(ownerB).get("version").asLong()).isZero();
  }

  private static final String STAGES = "/v1/clinic/pipeline/stages";
  private static final String REASONS = "/v1/clinic/loss-reasons";

  private static JsonNode funnel(Browser browser) throws Exception {
    var response = browser.get("/v1/clinic/pipeline");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private static JsonNode ok(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json(response);
  }

  private static long version(JsonNode funnel) {
    return funnel.get("version").asLong();
  }

  private static String stageId(JsonNode funnel, String key) {
    for (JsonNode stage : funnel.get("stages")) {
      if (key.equals(stage.get("key").asText())) return stage.get("id").asText();
    }
    throw new AssertionError("no stage " + key);
  }

  private static String byName(JsonNode funnel, String name) {
    for (JsonNode stage : funnel.get("stages")) {
      if (name.equals(stage.get("name").asText())) return stage.get("id").asText();
    }
    throw new AssertionError("no stage " + name);
  }

  private static int position(JsonNode funnel, String id) {
    for (JsonNode stage : funnel.get("stages")) {
      if (id.equals(stage.get("id").asText())) return stage.get("position").asInt();
    }
    throw new AssertionError("no stage " + id);
  }

  private static List<String> openIds(JsonNode funnel) {
    List<String> ids = new ArrayList<>();
    for (JsonNode stage : funnel.get("stages")) {
      if ("OPEN".equals(stage.get("kind").asText())) ids.add(stage.get("id").asText());
    }
    return ids;
  }

  private static void assertPositions(JsonNode funnel) {
    List<Integer> positions = new ArrayList<>();
    for (JsonNode stage : funnel.get("stages")) {
      if ("OPEN".equals(stage.get("kind").asText())) positions.add(stage.get("position").asInt());
    }
    List<Integer> expected = new ArrayList<>();
    for (int i = 1; i <= positions.size(); i++) expected.add(i);
    assertThat(positions).isEqualTo(expected);
  }

  private static String name(String name, long version) {
    return "{\"name\":\"" + name + "\",\"version\":" + version + "}";
  }

  private static String archived(boolean archived, long version) {
    return "{\"archived\":" + archived + ",\"version\":" + version + "}";
  }

  private static String archive(String stageId) {
    return STAGES + "/" + stageId + "/archived";
  }

  private static String order(List<String> ids, long version) {
    return "{\"stageIds\":[\"" + String.join("\",\"", ids) + "\"],\"version\":" + version + "}";
  }

  private static String lead(Browser browser, String contactId) throws Exception {
    var opened = browser.postJson(LEADS, "{\"contactId\":\"" + contactId + "\"}", true);
    assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
    return json(opened).get("id").asText();
  }

  @SafeVarargs
  private static List<Integer> race(Callable<Integer>... calls) throws Exception {
    var pool = Executors.newFixedThreadPool(calls.length);
    try {
      var barrier = new CyclicBarrier(calls.length);
      List<Future<Integer>> results = new ArrayList<>();
      for (Callable<Integer> call : calls) {
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  return call.call();
                }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  private static String stage(String stageId, long version) {
    return "{\"stageId\":\"" + stageId + "\",\"version\":" + version + "}";
  }

  private String contact(Browser browser, String phone) throws Exception {
    var created =
        browser.postJson(
            "/v1/clinic/contacts",
            "{\"name\":\"Lead Sintético\",\"phone\":\"" + phone + "\"}",
            true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  private static String code(HttpResponse<String> response) {
    return json(response).get("code").asText();
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
      return Files.createTempDirectory("branly-funnel-config-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
