package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.asyncrun.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationWorkerTest {
  private final Instant now = Instant.parse("2026-09-13T00:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final RunLease lease = new RunLease(UUID.randomUUID(), 7, 1);
  private final GenerationRunLeases leases = mock(GenerationRunLeases.class);
  private final GenerationPlanExecutor planner = mock(GenerationPlanExecutor.class);
  private final GenerationCompletionStore completions = mock(GenerationCompletionStore.class);
  private final Supervisor supervisor = new Supervisor();
  private final GenerationCandidateProjection insufficient =
      new GenerationCandidateProjection(
          now,
          "insufficient_feasible_routes",
          List.of(),
          new GenerationEvidence(Map.of(), Set.of()));
  private final RunExecutionPolicy policy =
      GenerationExecutionPolicy.forRequestTimeout(Duration.ofSeconds(165));

  private GenerationWorker worker() {
    when(leases.claimAvailable("generation-79", Duration.ofSeconds(180), 1))
        .thenReturn(List.of(lease));
    when(leases.heartbeat(lease, Duration.ofSeconds(180))).thenReturn(true);
    when(planner.execute(eq(lease.runId()), any())).thenReturn(insufficient);
    when(completions.complete(eq(lease), same(insufficient), any())).thenReturn(true);
    return new GenerationWorker(
        "generation-79", leases, planner, completions, supervisor, policy, clock, () -> 0.5);
  }

  @Test
  void supervisor가_먼저_종료돼도_실행스레드가_끝나기_전에는_추가_claim하지_않는다() throws Exception {
    var worker = worker();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(planner.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("합성 실행 해제 누락");
              return insufficient;
            });
    supervisor.pending = new CompletableFuture<>();
    worker.pollOnce();
    try (var pool = Executors.newSingleThreadExecutor()) {
      var task =
          pool.submit(
              () -> {
                try {
                  supervisor.executor.execute(lease, supervisor.deadline);
                } catch (RunLeaseLostException expected) {
                }
              });
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        supervisor.pending.completeExceptionally(new RunLeaseLostException());
        worker.pollOnce();
        verify(leases, times(1)).claimAvailable(anyString(), any(), anyInt());
      } finally {
        release.countDown();
      }
      task.get(2, TimeUnit.SECONDS);
    }
    verifyNoInteractions(completions);
    supervisor.pending = new CompletableFuture<>();
    worker.pollOnce();
    verify(leases, times(2)).claimAvailable(anyString(), any(), anyInt());
  }

  @Test
  void planner의_반환객체를_완료저장_경계로_그대로_전달한다() {
    var worker = worker();
    var success = mock(GenerationCandidateProjection.class);
    when(planner.execute(any(), any())).thenReturn(success);
    when(completions.complete(eq(lease), same(success), any())).thenReturn(true);
    worker.pollOnce();
    verify(completions).complete(lease, success, now.plusSeconds(675));
    verify(leases, never()).fail(any(), any());
  }

  @Test
  void 중단된_실행은_후보를_반환해도_저장하지_않는다() {
    var worker = worker();
    when(planner.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              Thread.currentThread().interrupt();
              return insufficient;
            });
    try {
      worker.pollOnce();
    } finally {
      Thread.interrupted();
    }
    verifyNoInteractions(completions);
    verify(leases).retry(lease, Duration.ofMillis(500), "ASYNC_RUN_DEADLINE_EXCEEDED");
  }

  @Test
  void 생성불가도_원자_완료저장만_호출하고_별도_성공전이는_하지_않는다() {
    var worker = worker();
    worker.pollOnce();
    assertThat(supervisor.deadline).isEqualTo(now.plusSeconds(675));
    assertThat(supervisor.interval).isEqualTo(Duration.ofSeconds(10));
    var order = inOrder(leases, planner, completions);
    order.verify(leases).claimAvailable("generation-79", Duration.ofSeconds(180), 1);
    order.verify(leases).heartbeat(lease, Duration.ofSeconds(180));
    order.verify(planner).execute(lease.runId(), supervisor.deadline);
    order.verify(completions).complete(lease, insufficient, supervisor.deadline);
    verify(leases, never()).fail(any(), any());
    verify(leases, never()).retry(any(), any(), any());
  }

  @Test
  void 실행중에는_추가_claim을_하지_않고_종료후에만_다시_poll한다() {
    var worker = worker();
    supervisor.pending = new CompletableFuture<>();
    worker.pollOnce();
    worker.pollOnce();
    verify(leases, times(1)).claimAvailable(anyString(), any(), anyInt());
    supervisor.pending.completeExceptionally(new RunLeaseLostException());
    supervisor.pending = null;
    worker.pollOnce();
    verify(leases, times(2)).claimAvailable(anyString(), any(), anyInt());
  }

  @Test
  void 실행전_점유권을_잃으면_MCP와_완료저장을_모두_하지_않는다() {
    var worker = worker();
    when(leases.heartbeat(lease, Duration.ofSeconds(180))).thenReturn(false);
    worker.pollOnce();
    verifyNoInteractions(planner, completions);
    verify(leases, never()).fail(any(), any());
  }

  @Test
  void 저장시_fence를_잃으면_다시_쓰기나_실패전이를_하지_않는다() {
    var worker = worker();
    when(completions.complete(any(), any(), any())).thenReturn(false);
    worker.pollOnce();
    verify(completions).complete(lease, insufficient, now.plusSeconds(675));
    verify(leases, never()).fail(any(), any());
    verify(leases, never()).retry(any(), any(), any());
  }

  @Test
  void 일시실패는_정형코드와_backoff만_저장한다() {
    var worker = worker();
    when(planner.execute(any(), any())).thenThrow(new RetryableRunException("MCP_TIMEOUT"));
    worker.pollOnce();
    verify(leases).retry(lease, Duration.ofMillis(500), "MCP_TIMEOUT");
    verifyNoInteractions(completions);
    verify(leases, never()).fail(any(), any());
  }

  @Test
  void 세번째_일시실패는_재시도하지_않고_종료한다() {
    var worker = worker();
    var third = new RunLease(lease.runId(), 9, 3);
    when(leases.claimAvailable(anyString(), any(), anyInt())).thenReturn(List.of(third));
    when(leases.heartbeat(third, Duration.ofSeconds(180))).thenReturn(true);
    when(planner.execute(any(), any())).thenThrow(new RetryableRunException("MCP_TIMEOUT"));
    worker.pollOnce();
    verify(leases).fail(third, "MCP_TIMEOUT");
    verify(leases, never()).retry(any(), any(), any());
  }

  @Test
  void 저장예외의_원문은_버리고_정형_실패코드만_사용한다() {
    var worker = worker();
    when(completions.complete(any(), any(), any()))
        .thenThrow(new IllegalStateException("합성 비공개 원문"));
    worker.pollOnce();
    verify(leases).fail(lease, "GENERATION_EXECUTION_FAILED");
  }

  @Test
  void 저장입력_불가의_정형코드는_보존한다() {
    var worker = worker();
    when(planner.execute(any(), any())).thenThrow(GenerationException.inputUnavailable());
    worker.pollOnce();
    verify(leases).fail(lease, "GENERATION_INPUT_UNAVAILABLE");
    verifyNoInteractions(completions);
  }

  @Test
  void 종료후에는_claim하지_않고_drain은_한번만_수행한다() {
    var worker = worker();
    worker.shutdown();
    worker.shutdown();
    worker.pollOnce();
    verify(leases, never()).claimAvailable(anyString(), any(), anyInt());
    assertThat(supervisor.shutdowns).isEqualTo(1);
  }

  private static final class Supervisor implements RunExecutionSupervisor {
    private AsyncRunExecutor executor;
    private Instant deadline;
    private Duration interval;
    private int shutdowns;
    private CompletableFuture<RunResultSource> pending;

    public CompletableFuture<RunResultSource> supervise(
        RunLease lease,
        Instant deadline,
        Duration interval,
        AsyncRunExecutor executor,
        BooleanSupplier heartbeat) {
      this.deadline = deadline;
      this.executor = executor;
      this.interval = interval;
      if (pending != null) return pending;
      try {
        return CompletableFuture.completedFuture(executor.execute(lease, deadline));
      } catch (RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }

    public void shutdown(Duration timeout) {
      shutdowns++;
    }
  }
}
