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
        .resideInAPackage("..controller..")
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
        .resideInAPackage("..controller..")
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
  void mvc_계층_클래스는_역할을_드러내는_이름을_사용한다() {
    classes()
        .that()
        .resideInAPackage("..controller..")
        .should()
        .haveSimpleNameEndingWith("Controller")
        .allowEmptyShould(true)
        .check(classes);
    classes()
        .that()
        .resideInAPackage("..service..")
        .should()
        .haveSimpleNameEndingWith("Service")
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
