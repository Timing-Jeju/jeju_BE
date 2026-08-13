package com.timingjeju.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

@Tag("architecture")
class ArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void setUp() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.timingjeju.api");
  }

  @Test
  void controller는_repository에_직접_의존하지_않는다() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..repository..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void controller는_service를_통해_동작한다() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..service..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void 도메인_사이에_순환_의존성이_없다() {
    slices()
        .matching("com.timingjeju.api.domain.(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void domain은_global의_내부_구현에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..global.config..", "..global.security..", "..global.logging..", "..global.util..")
        .allowEmptyShould(true)
        .check(classes);
  }

  @Test
  void 현재_사용자_application_계약은_Spring에_의존하지_않고_domain에서_사용할_수_있다() {
    JavaClasses contractClasses =
        new ClassFileImporter()
            .importPackages(
                "com.timingjeju.api.application.security", "com.timingjeju.api.domain.contract");

    noClasses()
        .that()
        .resideInAnyPackage("..application.security..", "..domain.contract..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.security..")
        .allowEmptyShould(false)
        .check(contractClasses);
  }

  @Test
  void 멱등성_application_port는_Spring과_JDBC_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.idempotency..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.idempotency..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void import_run_application_port는_Spring과_JDBC_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.importing..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.importing..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 소셜_로그인_domain은_Spring_Security나_global_보안_구현에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..domain.auth..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.security..", "..global.security..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 보안_구성과_공통_오류_writer는_Jackson2에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAnyPackage("..global.config..", "..global.error..", "..global.security..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.fasterxml.jackson..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 공통_오류_계약은_도메인_구현에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..global.error..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..domain..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void mvc_계층_클래스는_역할을_드러내는_이름을_사용한다() {
    classes()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .haveSimpleNameEndingWith("Controller")
        .allowEmptyShould(true)
        .check(classes);
    classes()
        .that()
        .resideInAPackage("..service..")
        .should()
        .haveSimpleNameEndingWith("Service")
        .orShould()
        .haveSimpleNameEndingWith("Gateway")
        .orShould()
        .haveSimpleNameEndingWith("Resolver")
        .orShould()
        .haveSimpleNameEndingWith("Provider")
        .orShould()
        .haveSimpleNameEndingWith("UserInfo")
        .allowEmptyShould(true)
        .check(classes);
    classes()
        .that()
        .resideInAPackage("..repository..")
        .should()
        .haveSimpleNameEndingWith("Repository")
        .allowEmptyShould(true)
        .check(classes);
  }
}
