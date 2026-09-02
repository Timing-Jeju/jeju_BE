package com.timingjeju.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.mutation.FirebaseApplicationDependencyMutation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
  void controller는_Spring_DAO_예외에_의존하지_않는다() {
    noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.dao..")
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
  void profile_provisioning_application과_JDBC_adapter는_의존_방향을_유지한다() {
    noClasses()
        .that()
        .resideInAPackage("..application.profile..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.profile..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("JdbcProfileProvisioningStore")
        .should()
        .resideInAPackage("..global.profile..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.profile..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void current_user_profile_application과_JDBC_adapter는_의존_방향을_유지한다() {
    noClasses()
        .that()
        .resideInAPackage("..application.profile..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.profile..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("JdbcCurrentUserProfileStore")
        .should()
        .resideInAPackage("..global.profile..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.profile..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 완료_공급자_data_health_application은_Spring과_JDBC_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.datahealth..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.datahealth..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 완료_공급자_Actuator_adapter는_application_service_방향을_유지한다() {
    classes()
        .that()
        .haveSimpleName("CompletedProviderDataHealthIndicator")
        .should()
        .resideInAPackage("..global.datahealth..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.datahealth..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("ExternalDataHealthEndpoint")
        .should()
        .resideInAPackage("..global.datahealth..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.datahealth..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void snapshot_retention_application과_JDBC_adapter는_의존_방향을_유지한다() {
    noClasses()
        .that()
        .resideInAPackage("..application.retention..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.retention..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("JdbcSnapshotRetentionRepository")
        .should()
        .resideInAPackage("..global.retention..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.retention..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void snapshot_retention_orchestrator는_Spring_Micrometer_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.retention..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "io.micrometer..", "..global.retention..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("SnapshotRetentionScheduler")
        .should()
        .resideInAPackage("..global.retention..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.retention..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void mobility_route_application은_Spring_JDBC_global_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.mobility..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "java.sql..", "..global..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void push_application_port는_Firebase와_Spring_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.push..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.push..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("FirebasePushMessageSender")
        .should()
        .resideInAPackage("..global.push.firebase..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.push..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void Firebase_SDK는_global_push_firebase_adapter_밖으로_누출되지_않는다() {
    firebaseSdkIsolationRule().check(classes);
  }

  @Test
  void Firebase_SDK_누출_mutation을_application_전체_경계가_탐지한다() {
    JavaClasses mutation =
        new ClassFileImporter().importClasses(FirebaseApplicationDependencyMutation.class);

    assertThatThrownBy(() -> firebaseSdkIsolationRule().check(mutation))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("FirebaseMessaging");
  }

  private static ArchRule firebaseSdkIsolationRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage("..global.push.firebase..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.google.firebase..", "com.google.auth..")
        .allowEmptyShould(false);
  }

  @Test
  void TourAPI_provenance_application_port는_Spring과_JDBC_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.tourapi..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global.tourapi..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void TourAPI_discovery_application은_Spring_global_adapter와_공개_Controller에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.tourapi.discovery..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..global..")
        .allowEmptyShould(false)
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..application.tourapi.discovery..", "..global.tourapi.discovery..")
        .should()
        .beAnnotatedWith(RestController.class)
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
  void 푸시_알림_application은_provider_Spring_global_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..application.notification..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "org.springframework.security..",
            "..global.notification..",
            "..global.security..",
            "..application.push..",
            "com.google.firebase..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 푸시_알림_domain은_Firebase_SpringSecurity_global_adapter에_의존하지_않는다() {
    noClasses()
        .that()
        .resideInAPackage("..domain.notification..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.security..",
            "..global.notification..",
            "..global.security..",
            "..application.push..",
            "com.google.firebase..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 푸시_알림_JDBC_crypto_adapter는_application_port_방향을_유지한다() {
    classes()
        .that()
        .haveSimpleName("RegistrationTokenProtectionFailure")
        .should()
        .resideInAPackage("..application.notification..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("JdbcPushNotificationStore")
        .should()
        .resideInAPackage("..global.notification..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.notification..")
        .allowEmptyShould(false)
        .check(classes);
    classes()
        .that()
        .haveSimpleName("AesGcmRegistrationTokenProtector")
        .should()
        .resideInAPackage("..global.notification..")
        .andShould()
        .dependOnClassesThat()
        .resideInAPackage("..application.notification..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void 푸시_eligibility의_세조회는_repeatable_read_snapshot을_공유한다() throws ReflectiveOperationException {
    Class<?> store =
        Class.forName("com.timingjeju.api.global.notification.JdbcPushNotificationStore");
    var method =
        store.getDeclaredMethod("findEligible", java.util.UUID.class, java.time.Instant.class);
    Transactional transaction = method.getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.readOnly()).isTrue();
    assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
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
