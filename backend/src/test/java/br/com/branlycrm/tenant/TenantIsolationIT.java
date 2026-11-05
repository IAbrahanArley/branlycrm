package br.com.branlycrm.tenant;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionPools;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.repositories.TenantRegistryRepository;
import br.com.branlycrm.tenant.secrets.FileTenantSecretReader;
import br.com.branlycrm.tenant.secrets.FileTenantSecretWriter;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import br.com.branlycrm.tenant.secrets.TenantSecretReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proof of clinic isolation (plano-isolamento-clinicas.md, section 4). Clinics are created by the
 * real provisioner with non-superuser roles, and every check logs in over TCP with real passwords.
 * The application datasource is the restricted worker role; only migrations and fixtures use the
 * container superuser.
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
class TenantIsolationIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantConnectionRepository connections;
  @Autowired TenantRegistryRepository registry;
  @Autowired TenantSecretReader secrets;

  JdbcTemplate superuser;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String control = POSTGRES.getDatabaseName();
    registry.add("spring.datasource.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.datasource.username", () -> WORKER);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", () -> control);
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

  @Autowired
  void superuser() {
    var source =
        new DriverManagerDataSource(
            TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    superuser = new JdbcTemplate(source);
  }

  @Test
  void runtimeCredentialsReachOnlyTheirOwnClinicDatabase() throws Exception {
    UUID adminA = account(true);
    UUID adminB = account(true);
    TenantDatabase a = enqueue(adminA, 5);
    TenantDatabase b = enqueue(adminB, 5);
    assertThat(provisioning.runPending()).isEqualTo(2);
    assertActive(a, adminA);
    assertActive(b, adminB);

    try (Connection connection = connections.open(a.tenantId(), adminA)) {
      assertThat(identity(connection)).containsExactly(a.databaseUser(), a.databaseName());
    }
    String control = POSTGRES.getDatabaseName();
    for (String forbidden : List.of(b.databaseName(), control, "postgres", "template1")) {
      assertThat(connectDenied(a, forbidden)).as("runtime A -> " + forbidden).isTrue();
    }
    for (String forbidden : List.of(a.databaseName(), control, "postgres")) {
      assertThat(connectDenied(b, forbidden)).as("runtime B -> " + forbidden).isTrue();
    }
    assertThatThrownBy(() -> connections.open(b.tenantId(), adminA))
        .isInstanceOf(TenantAccessException.class);

    try (Connection runtime = runtime(a, a.databaseName())) {
      for (String forbidden :
          List.of(
              "CREATE TABLE forbidden (id integer)",
              "CREATE TEMP TABLE forbidden (id integer)",
              "CREATE ROLE forbidden",
              "CREATE DATABASE forbidden",
              "UPDATE tenant_identity SET tenant_id = gen_random_uuid()",
              "DELETE FROM tenant_identity",
              "SELECT * FROM flyway_schema_history",
              "DELETE FROM flyway_schema_history",
              "SET ROLE " + TenantInfrastructure.owner(a),
              "GRANT " + TenantInfrastructure.owner(a) + " TO " + a.databaseUser())) {
        assertThatThrownBy(() -> runtime.createStatement().execute(forbidden))
            .as(forbidden)
            .isInstanceOf(SQLException.class)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("42501");
      }
    }
  }

  @Test
  void rolesMigrationsAndControlAccessFollowLeastPrivilege() throws Exception {
    UUID admin = account(true);
    TenantDatabase clinic = enqueue(admin, 5);
    provisioning.runPending();
    String owner = TenantInfrastructure.owner(clinic);

    try (Connection connection = TestPostgres.admin(POSTGRES, clinic.databaseName());
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT DISTINCT installed_by FROM flyway_schema_history WHERE success")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).as("tenant migrations run as owner").isEqualTo(owner);
      assertThat(rows.next()).isFalse();
    }
    assertThat(
            superuser.queryForList(
                """
                SELECT r.rolname FROM pg_auth_members a JOIN pg_roles r ON r.oid = a.roleid
                WHERE a.member = (SELECT oid FROM pg_roles WHERE rolname = ?)
                """,
                String.class,
                clinic.databaseUser()))
        .as("runtime role is member of nothing")
        .isEmpty();
    Map<String, Object> provisionerMembership =
        superuser.queryForMap(
            """
            SELECT bool_or(a.inherit_option) AS inherits, bool_or(a.set_option) AS sets
            FROM pg_auth_members a
            WHERE a.roleid = (SELECT oid FROM pg_roles WHERE rolname = ?)
              AND a.member = (SELECT oid FROM pg_roles WHERE rolname = ?)
            """,
            owner,
            PROVISIONER);
    assertThat(provisionerMembership).containsEntry("inherits", false).containsEntry("sets", true);
    assertThat(
            superuser.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = ?",
                Boolean.class,
                clinic.databaseUser()))
        .isFalse();

    assertThat(sqlStateOf(PROVISIONER, POSTGRES.getDatabaseName(), "SELECT 1"))
        .as("provisioner cannot reach the control database")
        .isEqualTo("42501");
    assertThat(
            sqlStateOf(
                WORKER, POSTGRES.getDatabaseName(), "SELECT password_hash FROM user_accounts"))
        .as("worker cannot read password hashes")
        .isEqualTo("42501");
  }

  @Test
  void failedProvisioningKeepsItsIdentityAndResumesWithoutDuplicates() throws Exception {
    UUID admin = account(false);
    TenantDatabase clinic = enqueue(admin, 2);
    assertThat(provisioning.runPending()).as("first attempt and its immediate retry").isEqualTo(2);
    assertThat(status(clinic)).isEqualTo("FAILED");
    assertThat(task(clinic))
        .containsEntry("status", "FAILED")
        .containsEntry("step", "ACTIVATION")
        .containsEntry("last_error", "ADMIN_NOT_ELIGIBLE");
    assertThat(memberships(clinic)).isZero();
    assertThatThrownBy(() -> connections.open(clinic.tenantId(), admin))
        .isInstanceOf(TenantAccessException.class);

    superuser.update("UPDATE user_accounts SET email_verified = true WHERE subject_id = ?", admin);
    assertThat(provisioning.retryFailed(clinic.tenantId())).isTrue();
    assertThat(provisioning.retryFailed(clinic.tenantId())).as("only FAILED resumes").isFalse();
    assertThat(provisioning.runPending()).isOne();
    assertActive(clinic, admin);

    // Replaying every step against existing objects must reuse them all.
    superuser.update(
        "UPDATE tenants SET status = 'PROVISIONING' WHERE tenant_id = ?", clinic.tenantId());
    superuser.update(
        "UPDATE tenant_provisioning_tasks SET status = 'PENDING', step = 'SECRET' WHERE tenant_id = ?",
        clinic.tenantId());
    assertThat(provisioning.runPending()).isOne();
    assertActive(clinic, admin);
    assertThat(
            superuser.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname IN (?, ?)",
                Integer.class,
                clinic.databaseUser(),
                TenantInfrastructure.owner(clinic)))
        .isEqualTo(2);
    assertThat(
            superuser.queryForObject(
                "SELECT count(*) FROM pg_database WHERE datname = ?",
                Integer.class,
                clinic.databaseName()))
        .isOne();
    assertThat(memberships(clinic)).isOne();
    assertThat(reader().read(clinic).orElseThrow().version()).isOne();
    try (Connection connection = TestPostgres.admin(POSTGRES, clinic.databaseName());
        var rows =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT (SELECT count(*) FROM flyway_schema_history WHERE success),"
                        + " (SELECT count(*) FROM tenant_identity)")) {
      rows.next();
      assertThat(rows.getInt(1)).isEqualTo(2);
      assertThat(rows.getInt(2)).isOne();
    }
    try (Connection connection = connections.open(clinic.tenantId(), admin)) {
      assertThat(identity(connection))
          .containsExactly(clinic.databaseUser(), clinic.databaseName());
    }
  }

  @Test
  void concurrentWorkersProvisionEachClinicOnce() throws Exception {
    UUID admin = account(true);
    TenantDatabase clinic = enqueue(admin, 5);
    int workers = 4;
    var barrier = new CyclicBarrier(workers);
    List<Future<Integer>> claims = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(workers)) {
      for (int i = 0; i < workers; i++) {
        claims.add(
            pool.submit(
                (Callable<Integer>)
                    () -> {
                      barrier.await();
                      return provisioning.runPending();
                    }));
      }
      int total = 0;
      for (Future<Integer> claim : claims) total += claim.get();
      assertThat(total).isOne();
    }
    assertActive(clinic, admin);
  }

  @Test
  void clinicConnectionsFailClosed() throws Exception {
    UUID admin = account(true);
    TenantDatabase clinic = enqueue(admin, 5);
    provisioning.runPending();

    assertThatThrownBy(() -> connections.open(clinic.tenantId(), UUID.randomUUID()))
        .as("subject without membership")
        .isInstanceOf(TenantAccessException.class);

    superuser.update(
        "UPDATE tenants SET status = 'SUSPENDED' WHERE tenant_id = ?", clinic.tenantId());
    assertThatThrownBy(() -> connections.open(clinic.tenantId(), admin))
        .as("suspended clinic")
        .isInstanceOf(TenantAccessException.class);
    superuser.update("UPDATE tenants SET status = 'ACTIVE' WHERE tenant_id = ?", clinic.tenantId());

    try (Connection connection = TestPostgres.admin(POSTGRES, clinic.databaseName())) {
      connection
          .createStatement()
          .execute("UPDATE tenant_identity SET tenant_id = '" + UUID.randomUUID() + "'");
    }
    assertThatThrownBy(() -> connections.open(clinic.tenantId(), admin))
        .as("database declaring another clinic")
        .isInstanceOf(SQLException.class)
        .hasMessage("Tenant database identity mismatch");

    var secret = reader().read(clinic).orElseThrow();
    new FileTenantSecretWriter(SECRETS.toString())
        .rotate(clinic, secret.version(), "synthetic-" + UUID.randomUUID());
    assertThatThrownBy(() -> connections.open(clinic.tenantId(), admin))
        .as("stored password differs from the role")
        .isInstanceOf(SQLException.class)
        .hasMessageNotContaining(secret.password());

    Files.delete(SECRETS.resolve(clinic.credentialReference()));
    assertThatThrownBy(() -> connections.open(clinic.tenantId(), admin))
        .as("missing secret")
        .isInstanceOf(TenantSecretException.class)
        .hasMessage("Tenant credentials unavailable");
  }

  @Test
  void rotatedSecretIsUsedFromTheNextOperationWithoutAbortingBorrowedConnections()
      throws Exception {
    UUID admin = account(true);
    TenantDatabase clinic = enqueue(admin, 5);
    provisioning.runPending();
    try (var pools = pools(5, 3, () -> 0)) {
      var repository = new TenantConnectionRepository(registry, secrets, pools);
      try (Connection before = repository.open(clinic.tenantId(), admin)) {
        String rotated = "synthetic-" + UUID.randomUUID();
        var current = reader().read(clinic).orElseThrow();
        new FileTenantSecretWriter(SECRETS.toString()).rotate(clinic, current.version(), rotated);
        superuser.execute("ALTER ROLE " + clinic.databaseUser() + " PASSWORD '" + rotated + "'");

        try (Connection after = repository.open(clinic.tenantId(), admin)) {
          assertThat(identity(after)).containsExactly(clinic.databaseUser(), clinic.databaseName());
        }
        assertThat(pools.openPools()).as("current pool plus the retiring one").isEqualTo(2);
        assertThat(identity(before)).as("borrowed connection survives rotation").isNotEmpty();
      }
      try (Connection ignored = repository.open(clinic.tenantId(), admin)) {
        assertThat(pools.openPools()).as("retired pool closed once idle").isOne();
      }
    }
  }

  @Test
  void globalCeilingFailsFastAndEvictsTheLeastRecentlyUsedIdlePool() throws Exception {
    UUID adminA = account(true);
    UUID adminB = account(true);
    TenantDatabase a = enqueue(adminA, 5);
    TenantDatabase b = enqueue(adminB, 5);
    provisioning.runPending();
    try (var pools = pools(1, 3, () -> 0)) {
      var repository = new TenantConnectionRepository(registry, secrets, pools);
      try (Connection busy = repository.open(a.tenantId(), adminA)) {
        assertThatThrownBy(() -> repository.open(b.tenantId(), adminB))
            .isInstanceOf(SQLTransientConnectionException.class)
            .hasMessage("Tenant connection capacity exhausted");
      }
      try (Connection connection = repository.open(b.tenantId(), adminB)) {
        assertThat(identity(connection)).containsExactly(b.databaseUser(), b.databaseName());
      }
      assertThat(pools.openPools()).isOne();
    }
  }

  @Test
  void perClinicLimitAndIdleExpiryBoundConnections() throws Exception {
    UUID adminA = account(true);
    UUID adminB = account(true);
    TenantDatabase a = enqueue(adminA, 5);
    TenantDatabase b = enqueue(adminB, 5);
    provisioning.runPending();
    var clock = new AtomicLong();
    try (var pools = pools(5, 1, clock::get)) {
      var repository = new TenantConnectionRepository(registry, secrets, pools);
      try (Connection only = repository.open(a.tenantId(), adminA)) {
        assertThatThrownBy(() -> repository.open(a.tenantId(), adminA))
            .as("second connection of the same clinic waits, then fails")
            .isInstanceOf(SQLTransientConnectionException.class);
      }
      clock.addAndGet(Duration.ofMinutes(11).toNanos());
      try (Connection ignored = repository.open(b.tenantId(), adminB)) {
        assertThat(pools.openPools()).as("clinic A pool expired after idling").isOne();
      }
    }
  }

  private TenantConnectionPools pools(int maxPools, int maxPerTenant, LongSupplier clock) {
    return new TenantConnectionPools(
        POSTGRES.getHost(),
        POSTGRES.getMappedPort(5432),
        maxPools,
        maxPerTenant,
        Duration.ofMinutes(10),
        clock);
  }

  private UUID account(boolean verified) {
    UUID id = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', ?)",
        id,
        id + "@example.test",
        verified);
    return id;
  }

  /** Stands in for onboarding, which will enqueue in the same control transaction. */
  private TenantDatabase enqueue(UUID admin, int maxAttempts) {
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    var database =
        new TenantDatabase(
            tenant,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        database.databaseName(),
        database.databaseUser(),
        database.credentialReference());
    superuser.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, ?)",
        tenant,
        admin,
        maxAttempts);
    return database;
  }

  private void assertActive(TenantDatabase clinic, UUID admin) {
    assertThat(status(clinic)).isEqualTo("ACTIVE");
    assertThat(task(clinic)).containsEntry("status", "DONE").containsEntry("step", "DONE");
    assertThat(
            superuser.queryForObject(
                "SELECT role FROM tenant_memberships WHERE tenant_id = ? AND subject_id = ?",
                String.class,
                clinic.tenantId(),
                admin))
        .isEqualTo("OWNER");
  }

  private String status(TenantDatabase clinic) {
    return superuser.queryForObject(
        "SELECT status FROM tenants WHERE tenant_id = ?", String.class, clinic.tenantId());
  }

  private Map<String, Object> task(TenantDatabase clinic) {
    return superuser.queryForMap(
        "SELECT status, step, last_error FROM tenant_provisioning_tasks WHERE tenant_id = ?",
        clinic.tenantId());
  }

  private int memberships(TenantDatabase clinic) {
    return superuser.queryForObject(
        "SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?",
        Integer.class,
        clinic.tenantId());
  }

  private boolean connectDenied(TenantDatabase clinic, String target) {
    try (Connection ignored = runtime(clinic, target)) {
      return false;
    } catch (SQLException exception) {
      return "42501".equals(exception.getSQLState());
    }
  }

  private Connection runtime(TenantDatabase clinic, String target) throws SQLException {
    return DriverManager.getConnection(
        TestPostgres.url(POSTGRES, target),
        clinic.databaseUser(),
        reader().read(clinic).orElseThrow().password());
  }

  private static String sqlStateOf(String role, String database, String sql) {
    try (Connection connection =
        DriverManager.getConnection(TestPostgres.url(POSTGRES, database), role, PASSWORD)) {
      connection.createStatement().execute(sql);
      return null;
    } catch (SQLException exception) {
      return exception.getSQLState();
    }
  }

  private static List<String> identity(Connection connection) throws SQLException {
    try (var rows =
        connection.createStatement().executeQuery("SELECT current_user, current_database()")) {
      rows.next();
      return List.of(rows.getString(1), rows.getString(2));
    }
  }

  private static FileTenantSecretReader reader() {
    return new FileTenantSecretReader(SECRETS.toString());
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-tenant-secrets-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** Derived owner role name, as created by the provisioner. */
  private static final class TenantInfrastructure {
    static String owner(TenantDatabase database) {
      return br.com.branlycrm.provisioning.repositories.TenantInfrastructureRepository.ownerRole(
          database);
    }
  }
}
