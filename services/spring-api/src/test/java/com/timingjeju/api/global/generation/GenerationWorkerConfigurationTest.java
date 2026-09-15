package com.timingjeju.api.global.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class GenerationWorkerConfigurationTest {
  private final GenerationRunLeases leases = mock(GenerationRunLeases.class);
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(GenerationWorkerConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(GenerationRunLeases.class, () -> leases)
          .withBean(GenerationPlanExecutor.class, () -> mock(GenerationPlanExecutor.class))
          .withBean(GenerationCompletionStore.class, () -> mock(GenerationCompletionStore.class));

  @Test
  void claim_오류는_원문이나_cause_없이_고정_실패코드만_기록한다(
      org.springframework.boot.test.system.CapturedOutput output) {
    when(leases.claimAvailable(anyString(), any(), eq(1)))
        .thenThrow(
            new IllegalStateException(
                "sensitive-sql-detail", new RuntimeException("sensitive-provider-body")));
    runner
        .withPropertyValues(
            "app.schedule-generation.enabled=true",
            "app.schedule-generation.worker.initial-delay=100000")
        .run(
            context -> {
              assertThatCode(
                      () ->
                          context
                              .getBean(GenerationWorkerConfiguration.GenerationPolling.class)
                              .poll())
                  .doesNotThrowAnyException();
              assertThat(output.getAll())
                  .contains("GENERATION_POLL_FAILED")
                  .doesNotContain("sensitive-sql-detail", "sensitive-provider-body");
            });
  }

  @Test
  void 실행이나_저장_연결이_없는_활성화는_접수만_가능한_상태로_시작하지_않는다() {
    new ApplicationContextRunner()
        .withUserConfiguration(GenerationWorkerConfiguration.class)
        .withPropertyValues("app.schedule-generation.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void 기능이_꺼지면_워커와_주기적_claim을_생성하지_않는다() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(GenerationWorker.class);
          verifyNoInteractions(leases);
        });
  }

  @Test
  void 활성화하면_주기적으로_claim하고_종료한_워커는_새_claim을_하지_않는다() {
    when(leases.claimAvailable(anyString(), any(), eq(1))).thenReturn(List.of());
    var reference = new java.util.concurrent.atomic.AtomicReference<GenerationWorker>();
    runner
        .withPropertyValues(
            "app.schedule-generation.enabled=true",
            "app.schedule-generation.worker.initial-delay=0",
            "app.schedule-generation.worker.fixed-delay=20")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(GenerationWorker.class);
              reference.set(context.getBean(GenerationWorker.class));
              verify(leases, timeout(2000).atLeastOnce())
                  .claimAvailable(anyString(), eq(java.time.Duration.ofSeconds(180)), eq(1));
            });
    clearInvocations(leases);
    reference.get().pollOnce();
    verifyNoInteractions(leases);
  }

  @Test
  void 접수_전용_테스트에서는_워커를_명시적으로_중지할_수_있다() {
    runner
        .withPropertyValues(
            "app.schedule-generation.enabled=true", "app.schedule-generation.worker.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(GenerationWorker.class);
              verifyNoInteractions(leases);
            });
  }
}
