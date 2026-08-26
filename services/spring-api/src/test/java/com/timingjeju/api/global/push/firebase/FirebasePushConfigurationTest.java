package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.push.PushMessageSender;
import com.timingjeju.api.application.push.PushMessagingDisabledException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class FirebasePushConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
          .withUserConfiguration(FirebasePushConfiguration.class);

  @Test
  void 기본과_false는_ADC나_network없이_disabled_sender를_제공한다() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(PushMessageSender.class);
          assertThat(context.getBean(PushMessageSender.class))
              .isInstanceOf(DisabledPushMessageSender.class);
          assertThatThrownBy(() -> context.getBean(PushMessageSender.class).send(null))
              .isInstanceOf(PushMessagingDisabledException.class);
        });
  }

  @Test
  void 활성화하면_project_id가_필수이고_오류에_입력값이나_credential을_노출하지_않는다() {
    runner
        .withPropertyValues("app.push.fcm.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("FIREBASE_PROJECT_ID는 FCM 활성화 환경에서 필수입니다.");
            });
  }

  @Test
  void 활성화한_project_id의_placeholder와_잘못된_형식은_ADC전에_fail_fast한다() {
    for (String invalid : new String[] {"your-project-id", "UPPER_PROJECT", "a"}) {
      runner
          .withBean(FirebaseAdminClientFactory.class, () -> (settings, clock) -> ignoredGateway())
          .withPropertyValues("app.push.fcm.enabled=true", "app.push.fcm.project-id=" + invalid)
          .run(
              context -> {
                assertThat(context).as(invalid).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("FIREBASE_PROJECT_ID는 실제 Firebase project ID 형식이어야 합니다.");
              });
    }
  }

  @Test
  void 활성화한_ADC초기화가_실패하면_fail_fast하고_비밀_예외원문을_숨긴다() {
    String sensitiveMaterial = "private-key-material-for-test";
    runner
        .withBean(
            FirebaseAdminClientFactory.class,
            () ->
                (settings, clock) -> {
                  throw new IllegalStateException(sensitiveMaterial);
                })
        .withPropertyValues("app.push.fcm.enabled=true", "app.push.fcm.project-id=timing-jeju-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure().toString()).doesNotContain(sensitiveMaterial);
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("FCM ADC 또는 secret mount 자격 증명을 초기화할 수 없습니다.");
            });
  }

  @Test
  void 활성_timeout은_닫힌_범위_밖이면_network_전에_fail_fast한다() {
    runner
        .withBean(FirebaseAdminClientFactory.class, () -> (settings, clock) -> ignoredGateway())
        .withPropertyValues(
            "app.push.fcm.enabled=true",
            "app.push.fcm.project-id=timing-jeju-test",
            "app.push.fcm.connect-timeout=99ms")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage("FCM_CONNECT_TIMEOUT은 100ms 이상 10초 이하여야 합니다.");
            });
  }

  private static FirebaseMessagingGateway ignoredGateway() {
    return message -> FirebaseCallResult.accepted("unused");
  }
}
