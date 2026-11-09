package br.com.branlycrm.onboarding;

import static br.com.branlycrm.onboarding.InvitationFixtures.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceptance edge cases as crm_control_runtime (ADR-028): concurrent acceptances of one token, the
 * agency's first-owner invitation activating an AWAITING_OWNER clinic, the authority checks on that
 * invitation, and the request limiter on the anonymous endpoints. A class of its own so its limiter
 * budget is independent of TeamInvitationIT.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.mail.provider=local",
      MfaFixture.KEY_PROPERTY
    })
class InvitationAcceptanceIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();
  static final Path MAIL = temporaryDirectory();

  @LocalServerPort int port;
  @Autowired PasswordEncoder encoder;

  InvitationFixtures fixtures;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String url = TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName());
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", () -> TestPostgres.RUNTIME);
    registry.add("spring.datasource.password", () -> TestPostgres.PASSWORD);
    registry.add("spring.flyway.url", () -> url);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("app.mail.local-directory", MAIL::toString);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @BeforeEach
  void resetDisposableFixtures() {
    fixtures =
        new InvitationFixtures(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword())),
            encoder,
            MAIL);
    fixtures.reset();
  }

  @Test
  @Order(1)
  void concurrentAcceptancesOfOneTokenCreateOneAccountAndOneMembership() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    UUID clinic = fixtures.clinic("Clínica concorrente", "AWAITING_OWNER", agency);
    String token = fixtures.agencyOwnerInvitation(clinic, "race@example.test", agency, null);
    int racers = 4;
    var barrier = new CyclicBarrier(racers);
    List<Future<Integer>> answers = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(racers)) {
      for (int i = 0; i < racers; i++) {
        Browser browser = anonymous();
        answers.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return accept(browser, token).statusCode();
                }));
      }
      int accepted = 0;
      for (Future<Integer> answer : answers) {
        int status = answer.get();
        assertThat(status).isIn(204, 404, 409);
        if (status == 204) accepted++;
      }
      assertThat(accepted).isOne();
    }
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_accounts WHERE email = 'race@example.test'",
                    Integer.class))
        .isOne();
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?",
                    Integer.class,
                    clinic))
        .isOne();
    assertThat(fixtures.status(clinic)).isEqualTo("ACTIVE");
  }

  @Test
  @Order(2)
  void agencyOwnerInvitationActivatesAnAwaitingClinicOrWaitsForProvisioning() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    UUID admin = fixtures.account("agency-admin@example.test", true, true);
    fixtures.agencyAdmin(agency, admin);
    UUID awaiting = fixtures.clinic("Clínica aguardando", "AWAITING_OWNER", agency);
    UUID provisioning = fixtures.clinic("Clínica provisionando", "PROVISIONING", agency);
    String awaitingToken =
        fixtures.agencyOwnerInvitation(awaiting, "gestor-a@example.test", agency, admin);
    String provisioningToken =
        fixtures.agencyOwnerInvitation(provisioning, "gestor-b@example.test", agency, admin);

    assertThat(accept(anonymous(), awaitingToken).statusCode()).isEqualTo(204);
    assertThat(fixtures.status(awaiting)).isEqualTo("ACTIVE");
    assertThat(fixtures.role(awaiting, fixtures.subject("gestor-a@example.test")))
        .isEqualTo("OWNER");
    assertThat(fixtures.role(awaiting, admin)).as("the agency gets no membership").isNull();

    assertThat(accept(anonymous(), provisioningToken).statusCode()).isEqualTo(204);
    assertThat(fixtures.status(provisioning))
        .as("the provisioner activates it when it finishes")
        .isEqualTo("PROVISIONING");
    assertThat(fixtures.role(provisioning, fixtures.subject("gestor-b@example.test")))
        .isEqualTo("OWNER");
  }

  @Test
  @Order(3)
  void agencyOwnerInvitationDiesWithItsIssuerAuthority() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    UUID otherAgency = fixtures.agency("ACTIVE");
    UUID admin = fixtures.account("agency-admin@example.test", true, true);
    fixtures.agencyAdmin(agency, admin);

    // The clinic already has an owner: the agency can no longer bring one in.
    UUID owned = fixtures.clinic("Clínica com dono", "ACTIVE", agency);
    fixtures.member(owned, fixtures.account("dono@example.test", true, true), "OWNER", true);
    String ownedToken =
        fixtures.agencyOwnerInvitation(owned, "intruso@example.test", agency, admin);
    // The clinic belongs to another agency.
    UUID foreign = fixtures.clinic("Clínica de outra agência", "AWAITING_OWNER", otherAgency);
    String foreignToken =
        fixtures.agencyOwnerInvitation(foreign, "gestor-c@example.test", agency, admin);
    // The issuer left the agency.
    UUID left = fixtures.clinic("Clínica emissor saiu", "AWAITING_OWNER", agency);
    String leftToken = fixtures.agencyOwnerInvitation(left, "gestor-d@example.test", agency, admin);

    Browser anonymous = anonymous();
    assertThat(code(accept(anonymous, ownedToken))).isEqualTo("INVITATION_INVALID");
    assertThat(code(accept(anonymous, foreignToken))).isEqualTo("INVITATION_INVALID");
    fixtures
        .jdbc()
        .update("UPDATE agency_memberships SET active = false WHERE subject_id = ?", admin);
    assertThat(code(accept(anonymous, leftToken))).isEqualTo("INVITATION_INVALID");
    // A suspended agency stops too, even for an invitation the platform issued.
    UUID suspendedAgency = fixtures.agency("ACTIVE");
    UUID suspendedClinic =
        fixtures.clinic("Clínica agência suspensa", "AWAITING_OWNER", suspendedAgency);
    String suspendedToken =
        fixtures.agencyOwnerInvitation(
            suspendedClinic, "gestor-e@example.test", suspendedAgency, null);
    fixtures
        .jdbc()
        .update("UPDATE agencies SET status = 'SUSPENDED' WHERE agency_id = ?", suspendedAgency);
    assertThat(code(accept(anonymous, suspendedToken))).isEqualTo("INVITATION_INVALID");

    for (UUID clinic : List.of(foreign, left, suspendedClinic)) {
      assertThat(fixtures.status(clinic)).isEqualTo("AWAITING_OWNER");
    }
    assertThat(fixtures.subject("intruso@example.test")).isNull();
  }

  @Test
  @Order(4)
  void agencyInvitationNeverReachesAClinicThatHadAnOwnerFailedOrItsOwnAdmin() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    UUID admin = fixtures.account("agency-admin@example.test", true, true);
    fixtures.agencyAdmin(agency, admin);

    // The only owner's account was deactivated: the clinic is still not the agency's to fill.
    UUID orphaned = fixtures.clinic("Clínica com dono desativado", "AWAITING_OWNER", agency);
    UUID formerOwner = fixtures.account("dono-antigo@example.test", false, true);
    fixtures.member(orphaned, formerOwner, "OWNER", true);
    String orphanedToken =
        fixtures.agencyOwnerInvitation(orphaned, "substituto@example.test", agency, admin);
    UUID failed = fixtures.clinic("Clínica com falha", "FAILED", agency);
    String failedToken =
        fixtures.agencyOwnerInvitation(failed, "gestor-f@example.test", agency, admin);
    UUID self = fixtures.clinic("Clínica autoindicada", "AWAITING_OWNER", agency);
    String selfToken =
        fixtures.agencyOwnerInvitation(self, "agency-admin@example.test", agency, admin);

    Browser anonymous = anonymous();
    assertThat(code(accept(anonymous, orphanedToken))).isEqualTo("INVITATION_INVALID");
    assertThat(code(accept(anonymous, failedToken))).isEqualTo("INVITATION_INVALID");
    Browser agencyAdmin = new Browser(port);
    agencyAdmin.csrf();
    assertThat(agencyAdmin.login("agency-admin@example.test", PASSWORD).statusCode())
        .isEqualTo(204);
    agencyAdmin.csrf();
    MfaFixture.complete(agencyAdmin, fixtures.jdbc());
    assertThat(code(accept(agencyAdmin, selfToken)))
        .as("an agency admin never becomes owner through its own agency")
        .isEqualTo("INVITATION_INVALID");
    assertThat(fixtures.status(self)).isEqualTo("AWAITING_OWNER");
    assertThat(fixtures.subject("substituto@example.test")).isNull();
  }

  @Test
  @Order(5)
  void twoInvitationsForTheSameNewAddressCreateOneAccountAndKeepTheOtherPending() throws Exception {
    UUID agency = fixtures.agency("ACTIVE");
    UUID first = fixtures.clinic("Clínica um", "AWAITING_OWNER", agency);
    UUID second = fixtures.clinic("Clínica dois", "AWAITING_OWNER", agency);
    List<String> tokens =
        List.of(
            fixtures.agencyOwnerInvitation(first, "dupla@example.test", agency, null),
            fixtures.agencyOwnerInvitation(second, "dupla@example.test", agency, null));
    var barrier = new CyclicBarrier(2);
    List<Future<HttpResponse<String>>> answers = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      for (String token : tokens) {
        Browser browser = anonymous();
        answers.add(
            pool.submit(
                () -> {
                  barrier.await();
                  return accept(browser, token);
                }));
      }
      List<String> outcomes = new ArrayList<>();
      for (var answer : answers) {
        HttpResponse<String> response = answer.get();
        outcomes.add(response.statusCode() == 204 ? "ACCEPTED" : code(response));
      }
      assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "INVITATION_LOGIN_REQUIRED");
    }
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM user_accounts WHERE email = 'dupla@example.test'",
                    Integer.class))
        .isOne();
    assertThat(
            fixtures
                .jdbc()
                .queryForObject(
                    "SELECT count(*) FROM invitations WHERE email = 'dupla@example.test'"
                        + " AND accepted_at IS NULL AND revoked_at IS NULL",
                    Integer.class))
        .as("the loser's invitation stays pending, to accept after logging in")
        .isOne();
  }

  @Test
  @Order(6)
  void anonymousInvitationEndpointsRequireCsrf() throws Exception {
    Browser browser = new Browser(port);
    var preview =
        browser.postJson(
            "/v1/invitations/preview", "{\"token\":\"" + "C".repeat(43) + "\"}", false);
    var accept =
        browser.postJson("/v1/invitations/accept", "{\"token\":\"" + "C".repeat(43) + "\"}", false);
    assertThat(preview.statusCode()).isEqualTo(403);
    assertThat(code(preview)).isEqualTo("CSRF_INVALID");
    assertThat(code(accept)).isEqualTo("CSRF_INVALID");
  }

  @Test
  @Order(Integer.MAX_VALUE)
  void anonymousInvitationEndpointsAreRateLimited() throws Exception {
    Browser anonymous = anonymous();
    String body = "{\"token\":\"" + "B".repeat(43) + "\"}";
    int limited = 0;
    for (int i = 0; i < 25; i++) {
      var answer = anonymous.postJson("/v1/invitations/preview", body, true);
      if (answer.statusCode() == 429) {
        limited++;
        assertThat(answer.headers().firstValue("Retry-After")).contains("60");
        assertThat(code(answer)).isEqualTo("RATE_LIMITED");
      }
    }
    assertThat(limited).isPositive();
  }

  private static HttpResponse<String> accept(Browser browser, String token) throws Exception {
    return browser.postJson(
        "/v1/invitations/accept",
        "{\"token\":\""
            + token
            + "\",\"displayName\":\"Synthetic Gestor\",\"password\":\""
            + PASSWORD
            + "\"}",
        true);
  }

  private Browser anonymous() throws Exception {
    Browser browser = new Browser(port);
    browser.csrf();
    return browser;
  }

  private static String code(HttpResponse<String> response) {
    return Browser.JSON.readTree(response.body()).get("code").asText();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-acceptance-mail");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
