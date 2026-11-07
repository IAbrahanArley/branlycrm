package br.com.branlycrm.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Team management and clinic RBAC over real HTTP (cookies, CSRF, Spring Security filters) against
 * PostgreSQL with the V6 last-owner triggers (CRM-012, docs/permissoes.md).
 *
 * <p>The login limiter allows 20 attempts per minute per address and per application context, so
 * the whole class stays well below that: each test logs in only the browsers it needs.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
class TeamManagementIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final String PASSWORD = "Synthetic team integration secret 2026!";
  private static final String LAST_OWNER = "BRL01";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  UUID clinicA;
  UUID clinicB;
  UUID ownerA1;
  UUID ownerA2;
  UUID managerA;
  UUID managerA2;
  UUID receptionA;
  UUID salesA;
  UUID professionalA;
  UUID inactiveA;
  UUID ownerB;
  UUID outsiderB;

  @BeforeEach
  void resetDisposableFixtures() {
    // Each class owns a disposable container; never clean the developer database. Suspend first:
    // the V6 trigger forbids removing the last owner of an ACTIVE clinic.
    // Only ACTIVE clinics need it; V8 refuses other transitions to SUSPENDED.
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE status = 'ACTIVE'");
    jdbc.update("DELETE FROM tenant_provisioning_tasks");
    jdbc.update("DELETE FROM tenant_memberships");
    jdbc.update("DELETE FROM tenants");
    jdbc.update("DELETE FROM user_accounts");

    ownerA1 = account("owner-a1@example.test");
    ownerA2 = account("owner-a2@example.test");
    managerA = account("manager-a@example.test");
    managerA2 = account("manager-a2@example.test");
    receptionA = account("reception-a@example.test");
    salesA = account("sales-a@example.test");
    professionalA = account("professional-a@example.test");
    inactiveA = account("inactive-a@example.test");
    ownerB = account("owner-b@example.test");
    outsiderB = account("sales-b@example.test");

    // Inserted directly as ACTIVE: the activation trigger fires only on UPDATE of status.
    clinicA = clinic("Clínica A", "ACTIVE");
    clinicB = clinic("Clínica B", "ACTIVE");
    member(clinicA, ownerA1, "OWNER", true);
    member(clinicA, ownerA2, "OWNER", true);
    member(clinicA, managerA, "MANAGER", true);
    member(clinicA, managerA2, "MANAGER", true);
    member(clinicA, receptionA, "ATTENDANT", true);
    member(clinicA, salesA, "ATTENDANT", true);
    member(clinicA, professionalA, "VIEWER", true);
    member(clinicA, inactiveA, "ATTENDANT", false);
    member(clinicB, ownerB, "OWNER", true);
    member(clinicB, outsiderB, "ATTENDANT", true);
  }

  @Test
  void currentClinicAndTeamListingFollowTheRoleMatrix() throws Exception {
    Browser anonymous = new Browser(port);
    assertThat(anonymous.get("/v1/auth/clinic").statusCode()).isEqualTo(401);
    assertThat(anonymous.get("/v1/clinic/team").statusCode()).isEqualTo(401);

    Browser owner = loggedIn("owner-a1@example.test");
    Browser manager = loggedIn("manager-a@example.test");
    Browser reception = loggedIn("reception-a@example.test");
    Browser sales = loggedIn("sales-a@example.test");
    Browser professional = loggedIn("professional-a@example.test");

    for (Browser browser : List.of(owner, manager, reception, sales, professional)) {
      assertThat(browser.get("/v1/auth/clinic").statusCode()).isEqualTo(409);
      assertThat(browser.get("/v1/clinic/team").statusCode()).isEqualTo(409);
      select(browser, clinicA);
    }

    JsonNode ownerClinic = current(owner, "OWNER");
    assertThat(ownerClinic.at("/permissions/TEAM_MANAGE").asText()).isEqualTo("ALL");
    assertThat(ownerClinic.at("/permissions/DATA_EXPORT").asText()).isEqualTo("ALL");
    JsonNode managerClinic = current(manager, "MANAGER");
    assertThat(managerClinic.at("/permissions/TEAM_VIEW").asText()).isEqualTo("ALL");
    assertThat(managerClinic.at("/permissions/DATA_EXPORT").asText()).isEqualTo("ALL");
    assertThat(managerClinic.at("/permissions/AUDIT_VIEW").asText()).isEqualTo("NONE");
    JsonNode receptionClinic = current(reception, "ATTENDANT");
    assertThat(receptionClinic.at("/permissions/TEAM_VIEW").asText()).isEqualTo("NONE");
    assertThat(receptionClinic.at("/permissions/LEAD_TAKE").asText()).isEqualTo("OWN");
    JsonNode salesClinic = current(sales, "ATTENDANT");
    assertThat(salesClinic.at("/permissions/SALE_REGISTER").asText()).isEqualTo("ALL");
    assertThat(salesClinic.at("/permissions/LEAD_ASSIGN").asText()).isEqualTo("NONE");
    JsonNode professionalClinic = current(professional, "VIEWER");
    assertThat(professionalClinic.at("/permissions/LEAD_WRITE").asText()).isEqualTo("NONE");
    assertThat(professionalClinic.at("/permissions/MONTHLY_REPORT_VIEW").asText()).isEqualTo("ALL");

    for (Browser browser : List.of(owner, manager)) {
      var response = browser.get("/v1/clinic/team");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body())
          .doesNotContainIgnoringCase("password")
          .doesNotContainIgnoringCase("hash")
          .doesNotContain("authVersion")
          .doesNotContain("sales-b@example.test")
          .doesNotContain("owner-b@example.test");
      JsonNode page = Browser.JSON.readTree(response.body());
      assertThat(fieldNames(page)).containsExactlyInAnyOrder("items", "page", "pageSize", "total");
      assertThat(page.get("page").asInt()).isOne();
      assertThat(page.get("pageSize").asInt()).isEqualTo(25);
      assertThat(page.get("total").asLong()).isEqualTo(8);
      JsonNode team = page.get("items");
      assertThat(team.isArray()).isTrue();
      assertThat(team.size()).as("active and inactive members of clinic A only").isEqualTo(8);
      List<String> subjects = new ArrayList<>();
      for (JsonNode member : team) {
        assertThat(fieldNames(member))
            .containsExactlyInAnyOrder("subjectId", "displayName", "email", "role", "active");
        subjects.add(member.get("subjectId").asText());
        if (member.get("subjectId").asText().equals(inactiveA.toString())) {
          assertThat(member.get("active").asBoolean()).isFalse();
          assertThat(member.get("role").asText()).isEqualTo("ATTENDANT");
        }
        if (member.get("subjectId").asText().equals(managerA.toString())) {
          assertThat(member.get("email").asText()).isEqualTo("manager-a@example.test");
          assertThat(member.get("role").asText()).isEqualTo("MANAGER");
          assertThat(member.get("active").asBoolean()).isTrue();
        }
      }
      assertThat(subjects)
          .doesNotContain(ownerB.toString(), outsiderB.toString())
          .contains(ownerA1.toString(), professionalA.toString());
    }
    for (Browser browser : List.of(reception, sales, professional)) {
      var denied = browser.get("/v1/clinic/team");
      assertThat(denied.statusCode()).isEqualTo(403);
      assertThat(denied.body()).doesNotContain("@example.test");
      assertThat(code(denied)).isEqualTo("CLINIC_ACCESS_DENIED");
    }
  }

  @Test
  void managerChangesOnlyOperationalMembersToOperationalRoles() throws Exception {
    Browser manager = selected("manager-a@example.test", clinicA);

    var changed = changeRole(manager, salesA, "VIEWER");
    assertThat(changed.statusCode()).isEqualTo(200);
    JsonNode view = Browser.JSON.readTree(changed.body());
    assertThat(view.get("subjectId").asText()).isEqualTo(salesA.toString());
    assertThat(view.get("role").asText()).isEqualTo("VIEWER");
    assertThat(view.get("active").asBoolean()).isTrue();
    assertThat(changed.body()).doesNotContainIgnoringCase("password");
    assertThat(role(clinicA, salesA)).isEqualTo("VIEWER");

    // Visible member, but the manager's role does not reach it: 403, nothing changes.
    assertThat(changeRole(manager, ownerA1, "ATTENDANT").statusCode()).isEqualTo(403);
    assertThat(changeRole(manager, managerA2, "ATTENDANT").statusCode()).isEqualTo(403);
    assertThat(changeRole(manager, salesA, "MANAGER").statusCode()).isEqualTo(403);
    assertThat(changeRole(manager, salesA, "OWNER").statusCode()).isEqualTo(403);
    assertThat(changeRole(manager, managerA, "ATTENDANT").statusCode()).isEqualTo(403);
    assertThat(manager.delete("/v1/clinic/team/" + ownerA1, true).statusCode()).isEqualTo(403);
    assertThat(manager.delete("/v1/clinic/team/" + managerA2, true).statusCode()).isEqualTo(403);
    assertThat(manager.delete("/v1/clinic/team/" + managerA, true).statusCode()).isEqualTo(403);
    assertThat(role(clinicA, ownerA1)).isEqualTo("OWNER");
    assertThat(role(clinicA, managerA2)).isEqualTo("MANAGER");
    assertThat(role(clinicA, managerA)).isEqualTo("MANAGER");
    assertThat(role(clinicA, salesA)).isEqualTo("VIEWER");

    // Invalid input and missing CSRF never reach the change.
    for (String retired : List.of("ADMIN", "RECEPTION", "SALES", "PROFESSIONAL")) {
      assertThat(changeRole(manager, salesA, retired).statusCode()).as(retired).isEqualTo(400);
    }
    assertThat(
            manager.putJson("/v1/clinic/team/" + salesA + "/role", "{\"role\":", true).statusCode())
        .isEqualTo(400);
    assertThat(manager.putJson("/v1/clinic/team/" + salesA + "/role", "{}", true).statusCode())
        .isEqualTo(400);
    assertThat(
            manager
                .putJson("/v1/clinic/team/not-a-uuid/role", "{\"role\":\"ATTENDANT\"}", true)
                .statusCode())
        .isEqualTo(400);
    assertThat(
            manager
                .putJson("/v1/clinic/team/" + salesA + "/role", "{\"role\":\"ATTENDANT\"}", false)
                .statusCode())
        .isEqualTo(403);
    assertThat(manager.delete("/v1/clinic/team/" + professionalA, false).statusCode())
        .isEqualTo(403);
    assertThat(role(clinicA, salesA)).isEqualTo("VIEWER");
    assertThat(active(clinicA, professionalA)).isTrue();

    // A member of another clinic, a random UUID and an inactive member answer the same 404.
    var foreign = changeRole(manager, outsiderB, "VIEWER");
    var absent = changeRole(manager, UUID.randomUUID(), "VIEWER");
    var inactive = changeRole(manager, inactiveA, "VIEWER");
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(absent.statusCode()).isEqualTo(404);
    assertThat(inactive.statusCode()).isEqualTo(404);
    assertThat(foreign.body()).isEqualTo(absent.body()).isEqualTo(inactive.body());
    assertThat(code(foreign)).isEqualTo("MEMBER_NOT_FOUND");
    var foreignDelete = manager.delete("/v1/clinic/team/" + outsiderB, true);
    var absentDelete = manager.delete("/v1/clinic/team/" + UUID.randomUUID(), true);
    assertThat(foreignDelete.statusCode()).isEqualTo(404);
    assertThat(absentDelete.statusCode()).isEqualTo(404);
    assertThat(foreignDelete.body()).isEqualTo(absentDelete.body());
    assertThat(role(clinicB, outsiderB)).isEqualTo("ATTENDANT");
    assertThat(active(clinicB, outsiderB)).isTrue();
    assertThat(role(clinicA, inactiveA)).isEqualTo("ATTENDANT");

    // Deactivating an operational member keeps the membership row (history), inactive.
    assertThat(manager.delete("/v1/clinic/team/" + professionalA, true).statusCode())
        .isEqualTo(204);
    assertThat(active(clinicA, professionalA)).isFalse();
    assertThat(changeRole(manager, professionalA, "ATTENDANT").statusCode()).isEqualTo(404);
  }

  @Test
  void ownerManagesOwnersButNobodyChangesThemselvesAndAClinicKeepsAnOwner() throws Exception {
    Browser ownerOne = selected("owner-a1@example.test", clinicA);
    assertThat(changeRole(ownerOne, ownerA1, "MANAGER").statusCode()).isEqualTo(403);
    assertThat(ownerOne.delete("/v1/clinic/team/" + ownerA1, true).statusCode()).isEqualTo(403);

    // Two owners: demoting the other works because the actor remains OWNER.
    assertThat(changeRole(ownerOne, ownerA2, "MANAGER").statusCode()).isEqualTo(200);
    assertThat(activeOwners(clinicA)).containsExactly(ownerA1);
    // Now the sole owner cannot leave through its own session.
    assertThat(changeRole(ownerOne, ownerA1, "MANAGER").statusCode()).isEqualTo(403);
    assertThat(ownerOne.delete("/v1/clinic/team/" + ownerA1, true).statusCode()).isEqualTo(403);
    assertThat(activeOwners(clinicA)).containsExactly(ownerA1);

    // Transfer: promote another member first, then the new owner deactivates the old one.
    var promoted = changeRole(ownerOne, managerA, "OWNER");
    assertThat(promoted.statusCode()).isEqualTo(200);
    assertThat(Browser.JSON.readTree(promoted.body()).get("role").asText()).isEqualTo("OWNER");
    // Same role is a no-op, still 200.
    assertThat(changeRole(ownerOne, managerA, "OWNER").statusCode()).isEqualTo(200);
    Browser ownerThree = selected("manager-a@example.test", clinicA);
    current(ownerThree, "OWNER");
    assertThat(ownerThree.delete("/v1/clinic/team/" + ownerA1, true).statusCode()).isEqualTo(204);
    assertThat(activeOwners(clinicA)).containsExactly(managerA);
    assertThat(active(clinicA, ownerA1)).isFalse();

    // The former owner's selection is revoked on the next request.
    assertThat(ownerOne.get("/v1/clinic/team").statusCode()).isEqualTo(403);
    assertThat(ownerOne.get("/v1/clinic/team").statusCode()).isEqualTo(409);
    assertThat(changeRole(ownerThree, managerA, "MANAGER").statusCode()).isEqualTo(403);
    assertThat(activeOwners(clinicA)).containsExactly(managerA);
  }

  @Test
  void triggersKeepTheLastOwnerOfAnActiveClinic() {
    // Sole owner of an ACTIVE clinic: demote, deactivate or delete fails with BRL01.
    assertThat(
            jdbc.update(
                "UPDATE tenant_memberships SET role = 'ATTENDANT' WHERE subject_id = ?", ownerA2))
        .isOne();
    assertLastOwner(
        "UPDATE tenant_memberships SET role = 'MANAGER' WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        ownerA1);
    assertLastOwner(
        "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        ownerA1);
    assertLastOwner(
        "DELETE FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ?", clinicA, ownerA1);
    // Moving the sole owner's row to another ACTIVE clinic also loses clinic A's owner.
    assertLastOwner(
        "UPDATE tenant_memberships SET tenant_id = ? WHERE tenant_id = ? AND subject_id = ?",
        clinicB,
        clinicA,
        ownerA1);
    assertThat(activeOwners(clinicA)).containsExactly(ownerA1);
    assertThat(activeOwners(clinicB)).containsExactly(ownerB);

    // An owner with an unverified account does not count as the remaining owner.
    jdbc.update("UPDATE user_accounts SET email_verified = false WHERE subject_id = ?", ownerA2);
    jdbc.update("UPDATE tenant_memberships SET role = 'OWNER' WHERE subject_id = ?", ownerA2);
    assertLastOwner(
        "UPDATE tenant_memberships SET role = 'MANAGER' WHERE tenant_id = ? AND subject_id = ?",
        clinicA,
        ownerA1);
    // Once verified, the second owner lets the first one go.
    jdbc.update("UPDATE user_accounts SET email_verified = true WHERE subject_id = ?", ownerA2);
    assertThat(
            jdbc.update(
                "UPDATE tenant_memberships SET role = 'MANAGER' WHERE tenant_id = ? AND subject_id = ?",
                clinicA,
                ownerA1))
        .isOne();

    // Outside ACTIVE the invariant does not block, but activation requires an owner again.
    jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", clinicA);
    assertThat(
            jdbc.update(
                "UPDATE tenant_memberships SET active = false WHERE tenant_id = ? AND subject_id = ?",
                clinicA,
                ownerA2))
        .isOne();
    assertLastOwner("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", clinicA);
    UUID provisioning = clinic("Clínica Nova", "PROVISIONING");
    assertLastOwner("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", provisioning);
    member(provisioning, ownerB, "VIEWER", true);
    assertLastOwner("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", provisioning);
    member(provisioning, ownerA1, "OWNER", true);
    assertThat(
            jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", provisioning))
        .isOne();
  }

  @Test
  void twoOwnersDemotingEachOtherConcurrentlyLeaveExactlyOneOwner() throws Exception {
    Browser first = selected("owner-a1@example.test", clinicA);
    Browser second = selected("owner-a2@example.test", clinicA);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 5; round++) {
        CyclicBarrier start = new CyclicBarrier(2);
        Future<HttpResponse<String>> one = pool.submit(demoteAfter(start, first, ownerA2));
        Future<HttpResponse<String>> two = pool.submit(demoteAfter(start, second, ownerA1));
        int a = one.get(30, TimeUnit.SECONDS).statusCode();
        int b = two.get(30, TimeUnit.SECONDS).statusCode();

        assertThat(List.of(a, b)).as("round %s", round).containsOnlyOnce(200);
        // The loser is serialized behind the clinic lock and re-reads its role: already MANAGER,
        // it no longer reaches an OWNER target (403). 409 would mean the last-owner check fired.
        int loser = a == 200 ? b : a;
        // The loser waits for the clinic lock, re-reads its own role (now MANAGER) and is refused.
        // Without the re-read under lock it would act as OWNER and get 409 instead.
        assertThat(loser).as("round %s loser", round).isEqualTo(403);
        assertThat(activeOwners(clinicA)).as("round %s", round).hasSize(1);

        // Restore both owners for the next round (adding an owner never trips the trigger).
        jdbc.update(
            "UPDATE tenant_memberships SET role = 'OWNER' WHERE tenant_id = ?"
                + " AND subject_id IN (?, ?)",
            clinicA,
            ownerA1,
            ownerA2);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void attendantAndViewerCannotChangeRolesNorDeactivateAnyone() throws Exception {
    Browser attendant = selected("reception-a@example.test", clinicA);
    Browser viewer = selected("professional-a@example.test", clinicA);
    for (Browser browser : List.of(attendant, viewer)) {
      for (UUID target : List.of(salesA, professionalA, receptionA, ownerA1)) {
        var change = changeRole(browser, target, "VIEWER");
        assertThat(change.statusCode()).isEqualTo(403);
        assertThat(code(change)).isEqualTo("CLINIC_ACCESS_DENIED");
        var deactivate = browser.delete("/v1/clinic/team/" + target, true);
        assertThat(deactivate.statusCode()).isEqualTo(403);
        assertThat(code(deactivate)).isEqualTo("CLINIC_ACCESS_DENIED");
      }
    }
    assertThat(role(clinicA, salesA)).isEqualTo("ATTENDANT");
    assertThat(role(clinicA, receptionA)).isEqualTo("ATTENDANT");
    assertThat(role(clinicA, ownerA1)).isEqualTo("OWNER");
    assertThat(active(clinicA, professionalA)).isTrue();
    assertThat(active(clinicA, salesA)).isTrue();
  }

  @Test
  void revokedMembershipAndRoleChangesApplyOnTheNextRequest() throws Exception {
    Browser owner = selected("owner-a1@example.test", clinicA);
    Browser sales = selected("sales-a@example.test", clinicA);
    Browser manager = selected("manager-a@example.test", clinicA);
    current(sales, "ATTENDANT");
    assertThat(manager.get("/v1/clinic/team").statusCode()).isEqualTo(200);

    assertThat(owner.delete("/v1/clinic/team/" + salesA, true).statusCode()).isEqualTo(204);
    assertThat(sales.get("/v1/auth/clinic").statusCode()).isEqualTo(403);
    // The revoked selection is dropped; reselecting is refused.
    assertThat(sales.get("/v1/auth/clinic").statusCode()).isEqualTo(409);
    assertThat(sales.put("/v1/auth/clinic/" + clinicA, true).statusCode()).isEqualTo(403);
    assertThat(active(clinicA, salesA)).isFalse();

    assertThat(changeRole(owner, managerA, "ATTENDANT").statusCode()).isEqualTo(200);
    assertThat(manager.get("/v1/clinic/team").statusCode()).isEqualTo(403);
    current(manager, "ATTENDANT");
    assertThat(changeRole(manager, professionalA, "ATTENDANT").statusCode()).isEqualTo(403);
    assertThat(role(clinicA, professionalA)).isEqualTo("VIEWER");
  }

  private static Callable<HttpResponse<String>> demoteAfter(
      CyclicBarrier start, Browser actor, UUID target) {
    return () -> {
      start.await(10, TimeUnit.SECONDS);
      return actor.putJson("/v1/clinic/team/" + target + "/role", "{\"role\":\"MANAGER\"}", true);
    };
  }

  private HttpResponse<String> changeRole(Browser browser, UUID target, String role)
      throws Exception {
    return browser.putJson(
        "/v1/clinic/team/" + target + "/role", "{\"role\":\"" + role + "\"}", true);
  }

  private JsonNode current(Browser browser, String role) throws Exception {
    var response = browser.get("/v1/auth/clinic");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = Browser.JSON.readTree(response.body());
    assertThat(body.get("tenantId").asText()).isEqualTo(clinicA.toString());
    assertThat(body.get("role").asText()).isEqualTo(role);
    List<String> expected = new ArrayList<>();
    for (ClinicPermission permission : ClinicPermission.values()) expected.add(permission.name());
    assertThat(fieldNames(body.get("permissions"))).containsExactlyInAnyOrderElementsOf(expected);
    for (JsonNode scope : body.get("permissions")) {
      assertThat(scope.asText()).isIn("ALL", "OWN", "NONE");
    }
    return body;
  }

  private Browser selected(String email, UUID clinic) throws Exception {
    Browser browser = loggedIn(email);
    select(browser, clinic);
    return browser;
  }

  private static void select(Browser browser, UUID clinic) throws Exception {
    assertThat(browser.put("/v1/auth/clinic/" + clinic, true).statusCode()).isEqualTo(200);
  }

  private Browser loggedIn(String email) throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(204);
    browser.csrf();
    return browser;
  }

  private static String code(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body()).get("code").asText();
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private void assertLastOwner(String sql, Object... args) {
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            failure -> {
              assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
              assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState())
                  .isEqualTo(LAST_OWNER);
            });
  }

  private Set<UUID> activeOwners(UUID clinic) {
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT subject_id FROM tenant_memberships WHERE tenant_id = ? AND role = 'OWNER'"
                + " AND active",
            UUID.class,
            clinic));
  }

  private String role(UUID clinic, UUID subject) {
    return jdbc.queryForObject(
        "SELECT role FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ?",
        String.class,
        clinic,
        subject);
  }

  private boolean active(UUID clinic, UUID subject) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT active FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ?",
            Boolean.class,
            clinic,
            subject));
  }

  private UUID account(String email) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, ?, ?, true)",
        id,
        email,
        "Synthetic " + email.substring(0, email.indexOf('@')),
        encoder.encode(PASSWORD));
    return id;
  }

  private UUID clinic(String name, String status) {
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "");
    jdbc.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference, status) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        name,
        "clinic_" + suffix,
        "tenant_" + suffix,
        "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT),
        status);
    return id;
  }

  private void member(UUID tenant, UUID subject, String role, boolean active) {
    jdbc.update(
        "INSERT INTO tenant_memberships (tenant_id, subject_id, role, active) VALUES (?, ?, ?, ?)",
        tenant,
        subject,
        role,
        active);
  }
}
