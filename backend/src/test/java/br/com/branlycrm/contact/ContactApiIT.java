package br.com.branlycrm.contact;

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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Contacts API over real HTTP (cookies, CSRF, MFA gate) on clinics created by the real provisioner
 * (CRM-021). The login limiter allows 20 attempts per minute per context, so every browser logs in
 * once for the whole class. All personal data is synthetic.
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
class ContactApiIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final String LOGIN_PASSWORD = "Synthetic contacts api secret 2026!";
  private static final String BASE = "/v1/clinic/contacts";

  /** Shared for the class: logged in once (see the class comment). */
  private static Fixture fixture;

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

  private record Fixture(
      UUID clinicA,
      UUID clinicB,
      Browser owner,
      Browser manager,
      Browser attendant,
      Browser viewer,
      Browser ownerB) {
    List<Browser> writers() {
      return List.of(owner, manager, attendant);
    }

    List<Browser> everyone() {
      return List.of(owner, manager, attendant, viewer);
    }
  }

  @BeforeEach
  void clinicsAndBrowsers() throws Exception {
    if (fixture != null) return;
    UUID ownerA = account("owner-a@example.test");
    UUID managerA = account("manager-a@example.test");
    UUID attendantA = account("attendant-a@example.test");
    UUID viewerA = account("viewer-a@example.test");
    UUID ownerB = account("owner-b@example.test");
    UUID clinicA = provision(ownerA);
    UUID clinicB = provision(ownerB);
    member(clinicA, managerA, "MANAGER");
    member(clinicA, attendantA, "ATTENDANT");
    member(clinicA, viewerA, "VIEWER");
    fixture =
        new Fixture(
            clinicA,
            clinicB,
            selected("owner-a@example.test", clinicA),
            selected("manager-a@example.test", clinicA),
            selected("attendant-a@example.test", clinicA),
            selected("viewer-a@example.test", clinicA),
            selected("owner-b@example.test", clinicB));
  }

  @Test
  void createDetailListAndDuplicateWarning() throws Exception {
    Browser owner = fixture.owner();
    var check = owner.postJson(BASE + "/phone-check", "{\"phone\":\"(11) 98001-0001\"}", true);
    assertThat(check.statusCode()).isEqualTo(200);
    assertThat(json(check).get("phone").asText()).isEqualTo("+5511980010001");
    assertThat(json(check).get("contact").isNull()).isTrue();

    var created =
        owner.postJson(
            BASE,
            body(
                "name", "Ana Sintética",
                "phone", "(11) 98001-0001",
                "email", "ana@example.test",
                "birthDate", "1990-05-17",
                "notes", "Prefere contato à tarde."),
            true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    JsonNode contact = json(created);
    String id = contact.get("id").asText();
    assertThat(created.headers().firstValue("Location").orElseThrow()).endsWith(BASE + "/" + id);
    assertThat(contact.get("phone").asText()).isEqualTo("+5511980010001");
    assertThat(contact.get("version").asLong()).isZero();
    assertThat(contact.at("/consent/WHATSAPP").asText()).isEqualTo("NONE");

    var duplicate = owner.postJson(BASE, body("name", "Outra", "phone", "+55 11 98001-0001"), true);
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(json(duplicate).get("code").asText()).isEqualTo("CONTACT_DUPLICATE_PHONE");
    assertThat(json(duplicate).get("contactId").asText()).isEqualTo(id);
    var warned = owner.postJson(BASE + "/phone-check", "{\"phone\":\"11980010001\"}", true);
    assertThat(json(warned).at("/contact/id").asText()).isEqualTo(id);

    for (Browser reader : fixture.everyone()) {
      var detail = reader.get(BASE + "/" + id);
      assertThat(detail.statusCode()).isEqualTo(200);
      assertThat(json(detail).get("notes").asText()).isEqualTo("Prefere contato à tarde.");
    }
    var list = fixture.viewer().get(BASE + "?q=ANA%20sint");
    assertThat(list.statusCode()).isEqualTo(200);
    JsonNode item = json(list).get("items").get(0);
    assertThat(item.get("id").asText()).isEqualTo(id);
    assertThat(item.has("notes")).as("lists never carry notes").isFalse();
    assertThat(item.has("birthDate")).as("lists never carry birth date").isFalse();
    assertThat(json(check).toString()).doesNotContain("notes");
  }

  /**
   * Every mutation follows docs/permissoes.md: VIEWER changes nothing, ATTENDANT does not archive.
   */
  @Test
  void everyRouteFollowsTheRoleMatrix() throws Exception {
    String contact = create(fixture.owner(), "Matriz Sintética", "(11) 98002-0001");
    String tag = createTag(fixture.owner(), "Matriz");
    String version = "0";

    Map<String, HttpResponse<String>> viewer = new LinkedHashMap<>();
    Browser v = fixture.viewer();
    viewer.put(
        "phone-check", v.postJson(BASE + "/phone-check", "{\"phone\":\"11980020099\"}", true));
    viewer.put("create", v.postJson(BASE, body("name", "X", "phone", "11980020098"), true));
    viewer.put(
        "update",
        v.putJson(
            BASE + "/" + contact,
            body("name", "X", "phone", "11980020001", "version", version),
            true));
    viewer.put(
        "archive", v.putJson(BASE + "/" + contact + "/archived", "{\"archived\":true}", true));
    viewer.put(
        "consent",
        v.postJson(
            BASE + "/" + contact + "/consents",
            "{\"channel\":\"EMAIL\",\"status\":\"OPT_IN\"}",
            true));
    viewer.put("tag", v.put(BASE + "/" + contact + "/tags/" + tag, true));
    viewer.put("untag", v.delete(BASE + "/" + contact + "/tags/" + tag, true));
    viewer.put("create tag", v.postJson("/v1/clinic/tags", "{\"name\":\"Leitura\"}", true));
    viewer.put("rename tag", v.putJson("/v1/clinic/tags/" + tag, "{\"name\":\"Y\"}", true));
    viewer.put("delete tag", v.delete("/v1/clinic/tags/" + tag, true));
    // The refusal does not reveal whether the id exists.
    viewer.put(
        "update unknown id",
        v.putJson(
            BASE + "/" + UUID.randomUUID(),
            body("name", "X", "phone", "11980020001", "version", "0"),
            true));
    assertThat(viewer).allSatisfy(ContactApiIT::assertRoleRefused);

    Browser a = fixture.attendant();
    assertRoleRefused(
        "attendant archive",
        a.putJson(BASE + "/" + contact + "/archived", "{\"archived\":true}", true));
    assertRoleRefused(
        "attendant unarchive",
        a.putJson(BASE + "/" + contact + "/archived", "{\"archived\":false}", true));
    assertRoleRefused(
        "attendant rename tag", a.putJson("/v1/clinic/tags/" + tag, "{\"name\":\"Y\"}", true));
    assertRoleRefused("attendant delete tag", a.delete("/v1/clinic/tags/" + tag, true));
    assertThat(a.put(BASE + "/" + contact + "/tags/" + tag, true).statusCode()).isEqualTo(200);
    assertThat(
            a.postJson(
                    BASE + "/" + contact + "/consents",
                    "{\"channel\":\"EMAIL\",\"status\":\"OPT_IN\"}",
                    true)
                .statusCode())
        .isEqualTo(201);
    assertThat(a.postJson("/v1/clinic/tags", "{\"name\":\"Do atendente\"}", true).statusCode())
        .isEqualTo(201);
    var edited =
        a.putJson(
            BASE + "/" + contact,
            body("name", "Matriz Editada", "phone", "11980020001", "version", version),
            true);
    assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);

    Browser m = fixture.manager();
    assertThat(m.putJson("/v1/clinic/tags/" + tag, "{\"name\":\"Matriz 2\"}", true).statusCode())
        .isEqualTo(200);
    assertThat(
            m.putJson(BASE + "/" + contact + "/archived", "{\"archived\":true}", true).statusCode())
        .isEqualTo(200);
    assertThat(
            m.putJson(BASE + "/" + contact + "/archived", "{\"archived\":false}", true)
                .statusCode())
        .isEqualTo(200);
    assertThat(m.delete("/v1/clinic/tags/" + tag, true).statusCode()).isEqualTo(204);

    for (Browser reader : fixture.everyone()) {
      assertThat(reader.get(BASE).statusCode()).isEqualTo(200);
      assertThat(reader.get(BASE + "/" + contact + "/consents").statusCode()).isEqualTo(200);
      assertThat(reader.get("/v1/clinic/tags").statusCode()).isEqualTo(200);
    }
  }

  @Test
  void editionArchiveAndStaleVersions() throws Exception {
    Browser owner = fixture.owner();
    String contact = create(owner, "Versão Sintética", "(21) 98003-0001");
    String other = create(owner, "Outra Sintética", "(21) 98003-0002");
    String tag = createTag(owner, "Versão");

    var updated =
        owner.putJson(
            BASE + "/" + contact,
            body(
                "name",
                "Versão Nova",
                "phone",
                "21980030001",
                "email",
                "v@example.test",
                "version",
                "0"),
            true);
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(json(updated).get("version").asLong()).isEqualTo(1);
    assertThat(json(updated).get("notes").isNull()).as("absent fields are cleared").isTrue();
    assertThat(
            code(
                owner.putJson(
                    BASE + "/" + contact,
                    body("name", "X", "phone", "21980030001", "version", "0"),
                    true)))
        .isEqualTo("CONTACT_STALE");
    var duplicate =
        owner.putJson(
            BASE + "/" + contact, body("name", "X", "phone", "21980030002", "version", "1"), true);
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(json(duplicate).get("contactId").asText()).isEqualTo(other);

    var archived = owner.putJson(BASE + "/" + contact + "/archived", "{\"archived\":true}", true);
    assertThat(json(archived).get("archivedAt").isNull()).isFalse();
    assertThat(
            code(
                owner.putJson(
                    BASE + "/" + contact,
                    body("name", "X", "phone", "21980030001", "version", "2"),
                    true)))
        .isEqualTo("CONTACT_ARCHIVED");
    assertThat(code(owner.put(BASE + "/" + contact + "/tags/" + tag, true)))
        .isEqualTo("CONTACT_ARCHIVED");
    assertThat(owner.delete(BASE + "/" + contact + "/tags/" + tag, true).statusCode())
        .as("a tag can always be taken off")
        .isEqualTo(200);
    assertThat(
            owner
                .postJson(
                    BASE + "/" + contact + "/consents",
                    "{\"channel\":\"WHATSAPP\",\"status\":\"OPT_OUT\"}",
                    true)
                .statusCode())
        .as("an opt-out is always recordable")
        .isEqualTo(201);
    assertThat(json(owner.get(BASE + "?q=Versao%20Nova")).get("total").asLong()).isZero();
    assertThat(
            json(owner.get(BASE + "?q=Versao%20Nova&includeArchived=true")).get("total").asLong())
        .isOne();
    var unarchived =
        owner.putJson(BASE + "/" + contact + "/archived", "{\"archived\":false}", true);
    assertThat(json(unarchived).get("archivedAt").isNull()).isTrue();
    assertThat(owner.put(BASE + "/" + contact + "/tags/" + tag, true).statusCode()).isEqualTo(200);
  }

  @Test
  void consentFromTheApiIsAlwaysRecordedAsManual() throws Exception {
    Browser attendant = fixture.attendant();
    String contact = create(fixture.owner(), "Consentimento API", "(31) 98004-0001");
    var recorded =
        attendant.postJson(
            BASE + "/" + contact + "/consents",
            "{\"channel\":\"WHATSAPP\",\"status\":\"OPT_IN\",\"source\":\"IMPORT\"}",
            true);
    assertThat(recorded.statusCode()).isEqualTo(201);
    JsonNode consents = json(recorded);
    assertThat(consents.at("/current/WHATSAPP").asText()).isEqualTo("OPT_IN");
    assertThat(consents.at("/history/0/source").asText()).isEqualTo("MANUAL");
    assertThat(consents.at("/history/0/recordedBy").asText()).isNotEmpty();
  }

  /** Clinic A's users never read, find, count, change nor link clinic B's data. */
  @Test
  void anotherClinicsContactsAndTagsAreInvisible() throws Exception {
    Browser ownerB = fixture.ownerB();
    String theirs = create(ownerB, "Paciente da Clínica B", "(41) 98005-0002");
    String theirTag = createTag(ownerB, "Exclusiva B");
    assertThat(ownerB.put(BASE + "/" + theirs + "/tags/" + theirTag, true).statusCode())
        .isEqualTo(200);
    Browser owner = fixture.owner();
    String mine = create(owner, "Paciente da Clínica A", "(41) 98005-0001");

    for (var response :
        List.of(
            owner.get(BASE + "/" + theirs),
            owner.putJson(
                BASE + "/" + theirs,
                body("name", "Invasor", "phone", "41980050009", "version", "0"),
                true),
            owner.putJson(BASE + "/" + theirs + "/archived", "{\"archived\":true}", true),
            owner.get(BASE + "/" + theirs + "/consents"),
            owner.postJson(
                BASE + "/" + theirs + "/consents",
                "{\"channel\":\"EMAIL\",\"status\":\"OPT_IN\"}",
                true),
            owner.delete(BASE + "/" + theirs + "/tags/" + theirTag, true))) {
      assertThat(code(response)).as(response.uri().getPath()).isEqualTo("CONTACT_NOT_FOUND");
    }
    for (var response :
        List.of(
            owner.put(BASE + "/" + mine + "/tags/" + theirTag, true),
            owner.putJson("/v1/clinic/tags/" + theirTag, "{\"name\":\"Invasor\"}", true),
            owner.delete("/v1/clinic/tags/" + theirTag, true))) {
      assertThat(code(response)).as(response.uri().getPath()).isEqualTo("TAG_NOT_FOUND");
    }
    assertThat(ids(owner.get(BASE + "?includeArchived=true&pageSize=100"))).doesNotContain(theirs);
    assertThat(json(owner.get(BASE + "?q=Cl%C3%ADnica%20B")).get("total").asLong()).isZero();
    assertThat(json(owner.get(BASE + "?q=980050002")).get("total").asLong()).isZero();
    assertThat(json(owner.get(BASE + "?tagId=" + theirTag)).get("total").asLong()).isZero();
    assertThat(json(owner.get("/v1/clinic/tags")).toString()).doesNotContain(theirTag);
    var check = owner.postJson(BASE + "/phone-check", "{\"phone\":\"41980050002\"}", true);
    assertThat(json(check).get("contact").isNull()).isTrue();
    // The same number is a different contact in each clinic.
    assertThat(
            owner
                .postJson(BASE, body("name", "Homônimo A", "phone", "41980050002"), true)
                .statusCode())
        .isEqualTo(201);

    try (Connection b = TestPostgres.admin(POSTGRES, databaseOf(fixture.clinicB()));
        var rows =
            b.createStatement()
                .executeQuery(
                    "SELECT (SELECT name FROM contacts WHERE id = '"
                        + theirs
                        + "'), (SELECT count(*) FROM contact_tags WHERE contact_id = '"
                        + theirs
                        + "'), (SELECT count(*) FROM contact_consents WHERE contact_id = '"
                        + theirs
                        + "'), (SELECT name FROM tags WHERE id = '"
                        + theirTag
                        + "')")) {
      rows.next();
      assertThat(rows.getString(1)).isEqualTo("Paciente da Clínica B");
      assertThat(rows.getInt(2)).isOne();
      assertThat(rows.getInt(3)).isZero();
      assertThat(rows.getString(4)).isEqualTo("Exclusiva B");
    }
  }

  @Test
  void invalidInputNamesTheFieldWithoutEchoingTheValue() throws Exception {
    Browser owner = fixture.owner();
    String contact = create(owner, "Validação Sintética", "(51) 98006-0001");
    Map<String, HttpResponse<String>> cases = new LinkedHashMap<>();
    cases.put("name", owner.postJson(BASE, body("name", " ", "phone", "51980060002"), true));
    cases.put("phone", owner.postJson(BASE, body("name", "Ana", "phone", "secret-123"), true));
    cases.put(
        "email",
        owner.postJson(
            BASE, body("name", "Ana", "phone", "51980060002", "email", "secret-at"), true));
    cases.put(
        "birthDate",
        owner.postJson(
            BASE, body("name", "Ana", "phone", "51980060002", "birthDate", "17/05/1990"), true));
    cases.put(
        "notes",
        owner.postJson(
            BASE, body("name", "Ana", "phone", "51980060002", "notes", "x".repeat(2001)), true));
    cases.put("body", owner.postJson(BASE, "{not json", true));
    cases.put(
        "version",
        owner.putJson(BASE + "/" + contact, body("name", "Ana", "phone", "51980060001"), true));
    cases.put("contactId", owner.get(BASE + "/not-a-uuid"));
    cases.put("q", owner.get(BASE + "?q=a"));
    cases.put("tagId", owner.get(BASE + "?tagId=nope"));
    cases.put("includeArchived", owner.get(BASE + "?includeArchived=yes"));
    cases.put("pageSize", owner.get(BASE + "?pageSize=101"));
    cases.put("archived", owner.putJson(BASE + "/" + contact + "/archived", "{}", true));
    cases.put(
        "channel",
        owner.postJson(
            BASE + "/" + contact + "/consents",
            "{\"channel\":\"SMS\",\"status\":\"OPT_IN\"}",
            true));
    cases.put(
        "status",
        owner.postJson(
            BASE + "/" + contact + "/consents", "{\"channel\":\"EMAIL\",\"status\":\"x\"}", true));
    cases.put("tagId ", owner.put(BASE + "/" + contact + "/tags/nope", true));
    for (var entry : cases.entrySet()) {
      HttpResponse<String> response = entry.getValue();
      assertThat(response.statusCode()).as(entry.getKey() + ": " + response.body()).isEqualTo(400);
      JsonNode error = json(response);
      assertThat(error.get("code").asText()).isEqualTo("INVALID_INPUT");
      assertThat(error.get("fields").toString()).contains("\"" + entry.getKey().strip() + "\"");
      assertThat(response.body()).doesNotContain("secret").doesNotContain("17/05/1990");
    }
  }

  @Test
  void tagCatalogIsUniquePerClinic() throws Exception {
    Browser owner = fixture.owner();
    String vip = createTag(owner, "VIP Sintético");
    var duplicate = owner.postJson("/v1/clinic/tags", "{\"name\":\" vip sintético \"}", true);
    assertThat(code(duplicate)).isEqualTo("TAG_DUPLICATE");
    assertThat(
            fixture
                .ownerB()
                .postJson("/v1/clinic/tags", "{\"name\":\"VIP Sintético\"}", true)
                .statusCode())
        .isEqualTo(201);
    String contact = create(owner, "Com Tag Sintética", "(61) 98007-0001");
    var tagged = owner.put(BASE + "/" + contact + "/tags/" + vip, true);
    assertThat(json(tagged).get(0).get("id").asText()).isEqualTo(vip);
    assertThat(ids(owner.get(BASE + "?tagId=" + vip))).containsExactly(contact);
    assertThat(owner.delete("/v1/clinic/tags/" + vip, true).statusCode()).isEqualTo(204);
    assertThat(json(owner.get(BASE + "/" + contact)).get("tags").size()).isZero();
    assertThat(code(owner.delete("/v1/clinic/tags/" + vip, true))).isEqualTo("TAG_NOT_FOUND");
  }

  @Test
  void sessionClinicCsrfAndSecondFactorAreRequired() throws Exception {
    Browser anonymous = new Browser(port);
    assertThat(anonymous.get(BASE).statusCode()).isEqualTo(401);

    Browser owner = fixture.owner();
    assertThat(
            owner
                .postJson(BASE, body("name", "Sem CSRF", "phone", "71980080001"), false)
                .statusCode())
        .isEqualTo(403);
    assertThat(owner.postJson("/v1/clinic/tags", "{\"name\":\"Sem CSRF\"}", false).statusCode())
        .isEqualTo(403);

    // An owner who has not proved the second factor reaches no contact route.
    UUID secondOwner = account("owner-a2@example.test");
    member(fixture.clinicA(), secondOwner, "OWNER");
    Browser unproven = new Browser(port);
    unproven.csrf();
    assertThat(unproven.login("owner-a2@example.test", LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    unproven.csrf();
    var gated = unproven.get(BASE);
    assertThat(gated.statusCode()).isIn(401, 403);
    assertThat(code(gated)).isIn("MFA_REQUIRED", "MFA_ENROLLMENT_REQUIRED");

    // A user without a selected clinic gets CLINIC_NOT_SELECTED, never another clinic's data.
    UUID loose = account("loose@example.test");
    member(fixture.clinicA(), loose, "VIEWER");
    Browser unselected = loggedIn("loose@example.test");
    for (var response :
        List.of(
            unselected.get(BASE),
            unselected.get("/v1/clinic/tags"),
            unselected.get(BASE + "/" + UUID.randomUUID()))) {
      assertThat(code(response)).isEqualTo("CLINIC_NOT_SELECTED");
    }
  }

  private static void assertRoleRefused(String route, HttpResponse<String> response) {
    assertThat(response.statusCode()).as(route + ": " + response.body()).isEqualTo(403);
    assertThat(code(response)).as(route).isEqualTo("CLINIC_ACCESS_DENIED");
  }

  @Test
  void pagesFollowTheNameOrderWithoutRepeats() throws Exception {
    Browser owner = fixture.owner();
    List<String> created = new ArrayList<>();
    for (String suffix : List.of("Carla", "Ana", "Bia")) {
      created.add(create(owner, "Paginada " + suffix, "(81) 9800" + created.size() + "-0001"));
    }
    var first = owner.get(BASE + "?q=Paginada&pageSize=2&page=1");
    var second = owner.get(BASE + "?q=Paginada&pageSize=2&page=2");
    var third = owner.get(BASE + "?q=Paginada&pageSize=2&page=3");
    assertThat(json(first).get("total").asLong()).isEqualTo(3);
    assertThat(json(first).get("page").asInt()).isOne();
    assertThat(json(first).get("pageSize").asInt()).isEqualTo(2);
    List<String> names = new ArrayList<>();
    json(first).get("items").forEach(item -> names.add(item.get("name").asText()));
    json(second).get("items").forEach(item -> names.add(item.get("name").asText()));
    assertThat(names).containsExactly("Paginada Ana", "Paginada Bia", "Paginada Carla");
    assertThat(json(third).get("items").size()).isZero();
    assertThat(json(third).get("total").asLong()).isEqualTo(3);
  }

  private String create(Browser browser, String name, String phone) throws Exception {
    var created = browser.postJson(BASE, body("name", name, "phone", phone), true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private String createTag(Browser browser, String name) throws Exception {
    var created =
        browser.postJson(
            "/v1/clinic/tags", Browser.JSON.writeValueAsString(Map.of("name", name)), true);
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    return json(created).get("id").asText();
  }

  private static String body(String... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put(pairs[i], "version".equals(pairs[i]) ? Long.valueOf(pairs[i + 1]) : pairs[i + 1]);
    }
    return Browser.JSON.writeValueAsString(values);
  }

  private static JsonNode json(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body());
  }

  private static String code(HttpResponse<String> response) {
    return json(response).get("code").asText();
  }

  private static List<String> ids(HttpResponse<String> response) {
    List<String> ids = new ArrayList<>();
    json(response).get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = loggedIn(email);
    var selection = browser.put("/v1/auth/clinic/" + clinic, true);
    assertThat(selection.statusCode()).as(selection.body()).isEqualTo(200);
    return browser;
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, LOGIN_PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    MfaFixture.complete(browser, jdbc);
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

  private static String databaseOf(UUID tenant) {
    return "clinic_" + tenant.toString().replace("-", "");
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-contact-api-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
