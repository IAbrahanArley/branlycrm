package br.com.branlycrm.provisioning;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.identity.mfa.crypto.MfaCrypto;
import br.com.branlycrm.provisioning.services.TenantMigrationRollout;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.secrets.FileTenantSecretWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole application context of {@code APP_MODE=tenant-migrate} (ADR-031): it starts with the
 * deployment roles and only the provisioner settings, without HTTP, API keys or worker beans, and
 * its command runs at startup (no clinic yet, so it succeeds).
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.docker.compose.enabled=false", "app.provisioning.rollout.enabled=true"})
class TenantMigrateModeIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();

  @Autowired ApplicationContext context;
  @Autowired TenantMigrationRollout rollout;

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
    Files.delete(SECRETS);
  }

  @Test
  void modeStartsWithTheCommandAndNoWorkerCapability() {
    assertThat(rollout.getExitCode()).as("startup run over zero clinics").isZero();
    assertThat(context.getBeanNamesForType(TenantProvisioningService.class)).isEmpty();
    assertThat(context.getBeanNamesForType(FileTenantSecretWriter.class)).isEmpty();
    assertThat(Arrays.stream(context.getBeanDefinitionNames()))
        .noneMatch(name -> name.contains("ProvisioningSchedule"));
    // The second-factor key exists only in the HTTP process.
    assertThat(context.getBeanNamesForType(MfaCrypto.class)).isEmpty();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-tenant-migrate-mode-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
