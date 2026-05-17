package com.athena.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces hexagonal architecture constraints at build time. A violation here means production code
 * broke a dependency rule — the build must fail.
 */
class LayerDependencyTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.athena");
  }

  // ── Domain purity ──────────────────────────────────────────────────────────

  @Test
  void domain_must_not_depend_on_spring() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAPackage("org.springframework..");
    rule.check(classes);
  }

  @Test
  void domain_must_not_depend_on_jakarta() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAPackage("jakarta..");
    rule.check(classes);
  }

  @Test
  void domain_must_not_depend_on_lombok() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAPackage("lombok..");
    rule.check(classes);
  }

  @Test
  void domain_must_not_depend_on_jackson() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAPackage("com.fasterxml.jackson..");
    rule.check(classes);
  }

  @Test
  void domain_must_not_depend_on_jpa_or_hibernate() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAnyPackage("javax.persistence..", "jakarta.persistence..", "org.hibernate..");
    rule.check(classes);
  }

  @Test
  void domain_must_not_depend_on_kafka() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..domain..")
            .should()
            .accessClassesThat()
            .resideInAPackage("org.apache.kafka..");
    rule.check(classes);
  }

  // ── Application layer ──────────────────────────────────────────────────────

  @Test
  void application_must_not_depend_on_spring_web() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..application..")
            .should()
            .accessClassesThat()
            .resideInAPackage("org.springframework.web..");
    rule.check(classes);
  }

  @Test
  void application_must_not_depend_on_spring_data() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..application..")
            .should()
            .accessClassesThat()
            .resideInAPackage("org.springframework.data..");
    rule.check(classes);
  }

  @Test
  void application_must_not_depend_on_spring_stereotype() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena..application..")
            .should()
            .accessClassesThat()
            .resideInAPackage("org.springframework.stereotype..");
    rule.check(classes);
  }

  // ── Naming conventions ─────────────────────────────────────────────────────

  @Test
  void controllers_must_reside_in_rest_adapter_package() {
    ArchRule rule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAPackage("com.athena.adapter.rest..");
    rule.check(classes);
  }

  @Test
  void repositories_must_reside_in_persistence_adapter_package() {
    ArchRule rule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .resideInAPackage("com.athena.adapter.persistence..");
    rule.check(classes);
  }

  @Test
  void listeners_must_reside_in_messaging_adapter_package() {
    ArchRule rule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Listener")
            .should()
            .resideInAPackage("com.athena.adapter.kafka..");
    rule.check(classes);
  }

  // ── Hexagonal: adapters must not depend on each other ─────────────────────

  @Test
  void rest_adapter_must_not_depend_on_other_adapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena.adapter.rest..")
            .should()
            .accessClassesThat()
            .resideInAnyPackage(
                "com.athena.adapter.grpc..",
                "com.athena.adapter.ws..",
                "com.athena.adapter.persistence..",
                "com.athena.adapter.kafka..",
                "com.athena.adapter.redis..");
    rule.check(classes);
  }

  @Test
  void adapters_must_not_depend_on_domain_directly_bypassing_application() {
    // Adapters are allowed to depend on domain value objects (read-only),
    // but must route all mutations through the application layer.
    // Controllers may use domain value objects (read-only); mutations must go through the application layer.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.athena.adapter..")
            .and()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .accessClassesThat()
            .resideInAPackage("com.athena..domain..")
            .because(
                "Controllers must use application services, not domain objects directly."
                    + " Map at the adapter boundary.");
    rule.check(classes);
  }
}
