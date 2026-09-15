package com.timingjeju.api.global.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore;
import com.timingjeju.api.global.mcp.*;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationRuntimeConfigurationTest {
  private final McpToolClient client = mock(McpToolClient.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(GenerationRuntimeConfiguration.class)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
          .withBean(JdbcTemplate.class, () -> jdbc)
          .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
          .withBean(
              GenerationTripInputRepository.class, () -> mock(GenerationTripInputRepository.class))
          .withBean(
              CommandInputSnapshotRepository.class,
              () -> mock(CommandInputSnapshotRepository.class))
          .withBean(GenerationPlaceResolver.class, () -> mock(GenerationPlaceResolver.class))
          .withBean(
              GenerationDayHistoryRepository.class,
              () -> mock(GenerationDayHistoryRepository.class));

  @Test
  void 기능_OFF와_접수전용_설정은_실행과_저장_어댑터를_등록하지_않는다() {
    for (var properties :
        java.util.List.of(
            new String[0],
            new String[] {
              "app.schedule-generation.enabled=true", "app.schedule-generation.worker.enabled=false"
            })) {
      runner
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(GenerationPlanExecutor.class)
                    .doesNotHaveBean(GenerationCompletionStore.class);
                verifyNoExternalCalls();
              });
    }
  }

  @Test
  void 활성화는_실제_MCP실행기와_트랜잭션_저장기를_조립한다() {
    runner
        .withPropertyValues("app.schedule-generation.enabled=true")
        .withBean(McpToolClient.class, () -> client)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(GenerationPlanExecutor.class))
                  .isInstanceOf(McpGenerationExecutor.class);
              assertThat(context.getBean(GenerationCompletionStore.class))
                  .isInstanceOf(JdbcGenerationCompletionStore.class);
              verifyNoExternalCalls();
            });
  }

  @Test
  void MCP_클라이언트가_없으면_활성화를_실패시킨다() {
    runner
        .withPropertyValues("app.schedule-generation.enabled=true")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void 조립한_워커는_스냅샷_누락을_실제_실행기로_판정하고_실패를_기록한다() {
    var leases = mock(GenerationRunLeases.class);
    var lease =
        new com.timingjeju.api.application.asyncrun.RunLease(java.util.UUID.randomUUID(), 1, 1);
    when(leases.claimAvailable(anyString(), any(), eq(1)))
        .thenReturn(java.util.List.of(lease))
        .thenReturn(java.util.List.of());
    when(leases.heartbeat(eq(lease), any())).thenReturn(true);
    runner
        .withUserConfiguration(GenerationWorkerConfiguration.class)
        .withPropertyValues(
            "app.schedule-generation.enabled=true",
            "app.schedule-generation.worker.initial-delay=0")
        .withBean(McpToolClient.class, () -> client)
        .withBean(GenerationRunLeases.class, () -> leases)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              verify(leases, timeout(2000)).fail(lease, "GENERATION_INPUT_UNAVAILABLE");
              verifyNoExternalCalls();
            });
  }

  private void verifyNoExternalCalls() {
    verifyNoInteractions(client);
    assertThat(mockingDetails(jdbc).getInvocations())
        .allSatisfy(
            invocation ->
                assertThat(invocation.getMethod().getName()).isEqualTo("afterPropertiesSet"));
  }
}
