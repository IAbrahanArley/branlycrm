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
