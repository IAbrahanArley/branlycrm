package br.com.branlycrm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final JavaClasses CODE =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("br.com.branlycrm");

  @Test
  void controllersAndServicesDoNotAccessDatabaseApis() {
    noClasses()
        .that()
        .resideInAnyPackage("..controllers..", "..services..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.jdbc..", "jakarta.persistence..", "java.sql..", "org.hibernate..")
        .check(CODE);
  }

  @Test
  void repositoriesDoNotDependOnServicesOrHttp() {
    noClasses()
        .that()
        .resideInAPackage("..repositories..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..controllers..",
            "..services..",
            "org.springframework.http..",
            "org.springframework.web..",
            "jakarta.servlet..",
            "..dto..")
        .check(CODE);
  }

  @Test
  void servicesHaveNoHttpDependencies() {
    noClasses()
        .that()
        .resideInAPackage("..services..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..controllers..",
            "org.springframework.http..",
            "org.springframework.web..",
            "jakarta.servlet..")
        .check(CODE);
  }

  /** ADR-016: the API holds read-only secret capability; writing belongs to the provisioner. */
  @Test
  void onlyTheProvisionerKnowsTheSecretWriter() {
    noClasses()
        .that()
        .resideOutsideOfPackages("..tenant.secrets..", "..provisioning..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("FileTenantSecretWriter")
        .check(CODE);
  }

  /** The API modules never reach provisioning code, which holds administrative credentials. */
  @Test
  void apiModulesDoNotDependOnProvisioning() {
    noClasses()
        .that()
        .resideInAnyPackage("br.com.branlycrm.identity..", "br.com.branlycrm.tenant..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("br.com.branlycrm.provisioning..")
        .check(CODE);
  }

  /**
   * ADR-018: the only EntityManager today is the central one, so any entity would land in the
   * control database. Clinic entities wait for the tenant persistence unit.
   */
  @Test
  void noJpaEntitiesUntilTenantPersistenceExists() {
    noClasses().should().beAnnotatedWith("jakarta.persistence.Entity").check(CODE);
  }

  /** Shared HTTP contract and helpers never reach into a business or infrastructure module. */
  @Test
  void commonDependsOnNoModule() {
    noClasses()
        .that()
        .resideInAPackage("br.com.branlycrm.common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "br.com.branlycrm.identity..",
            "br.com.branlycrm.tenant..",
            "br.com.branlycrm.provisioning..",
            "br.com.branlycrm.config..")
        .check(CODE);
  }

  @Test
  void domainDependsOnNoOtherLayer() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..controllers..",
            "..services..",
            "..repositories..",
            "..dto..",
            "org.springframework..")
        .check(CODE);
  }
}
