package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.service.ProfileImageCleanupService;
import com.timingjeju.api.application.profile.service.ProfileImageOrphanService;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

@Tag("unit")
@ExtendWith(OutputCaptureExtension.class)
class ProfileImageMaintenanceRunnerContractTest {

  private static final ProfileImageStorageSettings ENABLED_STORAGE =
      ProfileImageStorageSettings.enabled(
          URI.create("https://project.example.invalid"),
          "unit-key",
          Duration.ofSeconds(1),
          Duration.ofSeconds(1));
  private static final ProfileImageStorageSettings DISABLED_STORAGE =
      new ProfileImageStorageSettings(
          false, null, null, Duration.ofSeconds(1), Duration.ofSeconds(1));

  private final ProfileImageCleanupService cleanup = mock(ProfileImageCleanupService.class);
  private final ProfileImageOrphanService orphan = mock(ProfileImageOrphanService.class);
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ProfileImageMaintenanceConfiguration.class)
          .withBean(ProfileImageCleanupService.class, () -> cleanup)
          .withBean(ProfileImageOrphanService.class, () -> orphan);

  @Test
  void provider_storage가_없거나_disabled이면_maintenance_flag와_무관하게_optional이다() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ProfileImageMaintenanceRunner.class);
          assertThat(context)
              .doesNotHaveBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        });
    contextRunner
        .withPropertyValues("app.profile-image.maintenance.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ProfileImageMaintenanceRunner.class);
            });
    contextRunner
        .withBean(ProfileImageStorageSettings.class, () -> DISABLED_STORAGE)
        .withPropertyValues("app.profile-image.maintenance.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ProfileImageMaintenanceRunner.class);
            });
  }

  @Test
  void local은_storage가_enabled여도_missing_or_false_maintenance를_허용한다() {
    for (String profile : java.util.List.of("local", "local-hs256")) {
      for (String value : java.util.List.of("missing", "false")) {
        ApplicationContextRunner local =
            contextRunner
                .withBean(ProfileImageStorageSettings.class, () -> ENABLED_STORAGE)
                .withPropertyValues("spring.profiles.active=" + profile);
        if ("false".equals(value)) {
          local = local.withPropertyValues("app.profile-image.maintenance.enabled=false");
        }
        local.run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ProfileImageMaintenanceRunner.class);
            });
      }
    }
  }

  @Test
  void production분류는_storage_enabled일때_missing_or_false_maintenance를_fail_fast한다() {
    for (String profile : new String[] {null, "staging", "test", "prod", "production"}) {
      ApplicationContextRunner production =
          contextRunner.withBean(ProfileImageStorageSettings.class, () -> ENABLED_STORAGE);
      if (profile != null) {
        production = production.withPropertyValues("spring.profiles.active=" + profile);
      }
      production.run(context -> assertMaintenanceRequiredFailure(context.getStartupFailure()));
      production
          .withPropertyValues("app.profile-image.maintenance.enabled=false")
          .run(context -> assertMaintenanceRequiredFailure(context.getStartupFailure()));
    }
  }

  @Test
  void production_storage_enabled와_maintenance_true는_scheduling과_runner를_정확히_하나_연결한다() {
    contextRunner
        .withBean(ProfileImageStorageSettings.class, () -> ENABLED_STORAGE)
        .withPropertyValues("app.profile-image.maintenance.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ProfileImageMaintenanceRunner.class);
              assertThat(context)
                  .hasBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
            });
  }

  @Test
  void typed_property_default와_Scheduled_placeholder는_delay와_initial_delay를_고정한다()
      throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ProfileImageMaintenanceConfiguration.class);
      org.springframework.test.context.support.TestPropertySourceUtils
          .addInlinedPropertiesToEnvironment(context, "app.profile-image.maintenance.enabled=true");
      context.registerBean(ProfileImageCleanupService.class, () -> cleanup);
      context.registerBean(ProfileImageOrphanService.class, () -> orphan);
      context.registerBean(ProfileImageStorageSettings.class, () -> ENABLED_STORAGE);
      context.refresh();

      ProfileImageMaintenanceProperties properties =
          context.getBean(ProfileImageMaintenanceProperties.class);
      assertThat(properties.cleanupFixedDelay()).isEqualTo(Duration.ofMinutes(1));
      assertThat(properties.orphanFixedDelay()).isEqualTo(Duration.ofHours(24));
      assertThat(properties.initialDelay()).isEqualTo(Duration.ofMinutes(1));
    }

    Scheduled cleanupSchedule =
        ProfileImageMaintenanceRunner.class
            .getDeclaredMethod("cleanupTick")
            .getAnnotation(Scheduled.class);
    Scheduled orphanSchedule =
        ProfileImageMaintenanceRunner.class
            .getDeclaredMethod("orphanTick")
            .getAnnotation(Scheduled.class);
    assertThat(cleanupSchedule.fixedDelayString())
        .isEqualTo("${app.profile-image.maintenance.cleanup-fixed-delay:PT1M}");
    assertThat(orphanSchedule.fixedDelayString())
        .isEqualTo("${app.profile-image.maintenance.orphan-fixed-delay:PT24H}");
    assertThat(cleanupSchedule.initialDelayString())
        .isEqualTo("${app.profile-image.maintenance.initial-delay:PT1M}");
    assertThat(orphanSchedule.initialDelayString())
        .isEqualTo("${app.profile-image.maintenance.initial-delay:PT1M}");
  }

  @Test
  void cleanup과_orphan_tick은_동시에_실행되지_않는다() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(cleanup.runOnce())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(2, TimeUnit.SECONDS);
              return 1;
            });
    ProfileImageMaintenanceRunner runner = new ProfileImageMaintenanceRunner(cleanup, orphan);

    try (var executor = Executors.newSingleThreadExecutor()) {
      var first = executor.submit(runner::cleanupTick);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      runner.orphanTick();
      release.countDown();
      first.get(1, TimeUnit.SECONDS);
    }

    verify(cleanup).runOnce();
    verify(orphan, never()).scanOnce();
  }

  @Test
  void provider_failure는_sanitized_log후_lock을_해제하고_다음_tick이_회복한다(CapturedOutput output) {
    String sensitiveMarker = "service-role-" + java.util.UUID.randomUUID();
    when(cleanup.runOnce())
        .thenThrow(
            new IllegalStateException(
                sensitiveMarker + " SELECT storage.objects",
                ProfileImageException.storageUnavailable()))
        .thenReturn(1);
    ProfileImageMaintenanceRunner runner = new ProfileImageMaintenanceRunner(cleanup, orphan);

    runner.cleanupTick();
    runner.cleanupTick();

    verify(cleanup, times(2)).runOnce();
    assertThat(output).contains("profile_image_cleanup scheduled cycle failed");
    assertThat(output)
        .doesNotContain(sensitiveMarker, "service-role-", "SELECT", "storage.objects");
  }

  private static void assertMaintenanceRequiredFailure(Throwable failure) {
    assertThat(failure)
        .isNotNull()
        .hasMessageContaining("profile image maintenance")
        .hasMessageContaining("production");
  }
}
