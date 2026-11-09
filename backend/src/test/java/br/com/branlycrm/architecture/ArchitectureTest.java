package br.com.branlycrm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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
        .resideInAnyPackage(
            "br.com.branlycrm.identity..",
            "br.com.branlycrm.tenant..",
            "br.com.branlycrm.agency..",
            "br.com.branlycrm.onboarding..",
            "br.com.branlycrm.platform..")
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
            "br.com.branlycrm.agency..",
            "br.com.branlycrm.onboarding..",
            "br.com.branlycrm.platform..",
            "br.com.branlycrm.provisioning..",
            "br.com.branlycrm.config..")
        .check(CODE);
  }

  /**
   * Onboarding works on the control database only: it never opens a clinic database, so an
   * invitation flow can never read patient data (ADR-026, ADR-028).
   */
  @Test
  void onboardingNeverOpensClinicDatabases() {
    noClasses()
        .that()
        .resideInAPackage("br.com.branlycrm.onboarding..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("TenantConnection")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("TenantSecretReader")
        .check(CODE);
  }

  /** The agency module reads the registry only; it never opens a clinic database (ADR-026). */
  @Test
  void agenciesNeverOpenClinicDatabases() {
    noClasses()
        .that()
        .resideInAPackage("br.com.branlycrm.agency..")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameStartingWith("TenantConnection")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("TenantSecretReader")
        .check(CODE);
  }

  /** The platform command and its credential stay out of every other module (ADR-028). */
  @Test
  void nothingDependsOnThePlatformCommand() {
    noClasses()
        .that()
        .resideOutsideOfPackage("br.com.branlycrm.platform..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("br.com.branlycrm.platform..")
        .check(CODE);
  }

  /**
   * CRM-016: the second factor decides its obligation with its own SQL and never reaches the
   * agency, onboarding or platform modules, which depend on identity.
   */
  @Test
  void secondFactorDependsOnNoBusinessModule() {
    noClasses()
        .that()
        .resideInAPackage("br.com.branlycrm.identity.mfa..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "br.com.branlycrm.agency..",
            "br.com.branlycrm.onboarding..",
            "br.com.branlycrm.platform..")
        .check(CODE);
  }

  /** Second-factor keys, secrets and recovery codes are handled only inside identity. */
  @Test
  void secondFactorCryptoStaysInIdentity() {
    noClasses()
        .that()
        .resideOutsideOfPackage("br.com.branlycrm.identity..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("br.com.branlycrm.identity.mfa.crypto..")
        .check(CODE);
  }

  /** Modules form a hierarchy; onboarding orchestrates and nothing depends back on it. */
  @Test
  void modulesAreFreeOfCycles() {
    slices().matching("br.com.branlycrm.(*)..").should().beFreeOfCycles().check(CODE);
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
