package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.timingjeju.api.application.generation.service.GenerationQueryService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationQueryServiceTest {
  private final UUID owner = UUID.randomUUID(), trip = UUID.randomUUID(), run = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-10-10T00:00:00Z");
  private final GenerationRunReader reader = mock(GenerationRunReader.class);
  private final GenerationQueryService service =
      new GenerationQueryService(reader, Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void 성공은_서로_다른_세_전략과_후보만_노출하며_부분결과는_거부한다() {
    var candidates = new ArrayList<GenerationRunReader.SavedCandidate>();
    int rank = 1;
    for (String strategy : List.of("balanced", "relaxed", "experience_max")) {
      candidates.add(
          new GenerationRunReader.SavedCandidate(
              UUID.randomUUID(),
              UUID.randomUUID(),
              rank++,
              strategy,
              new BigDecimal("89.25"),
              "feasible",
              "검증된 후보 설명",
              now,
              now.plus(Duration.ofHours(24))));
    }
    var valid = result("success", candidates);
    when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(valid));
    assertThat(service.read(owner, trip, run).candidates()).containsExactlyElementsOf(candidates);
    // 후보 적용은 24시간에 만료되지만 작업 조회의 7일 보존과 혼동하지 않는다.
    var later =
        new GenerationQueryService(
            reader, Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC));
    assertThat(later.read(owner, trip, run).candidates()).containsExactlyElementsOf(candidates);
    assertThatThrownBy(() -> valid.candidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    for (var invalid :
        List.of(
            candidates.subList(0, 2),
            List.of(candidates.get(0), candidates.get(0), candidates.get(2)))) {
      when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(result("success", invalid)));
      assertThatThrownBy(() -> service.read(owner, trip, run))
          .hasMessage("ASYNC_RESULT_TEMPORARILY_UNAVAILABLE");
    }
    when(reader.findOwned(owner, trip, run))
        .thenReturn(Optional.of(result("insufficient_feasible_routes", candidates)));
    assertThatThrownBy(() -> service.read(owner, trip, run))
        .hasMessage("ASYNC_RESULT_TEMPORARILY_UNAVAILABLE");
  }

  private GenerationRunReader.SavedRun result(
      String outcome, List<GenerationRunReader.SavedCandidate> candidates) {
    var saved = saved("succeeded", now.plus(Duration.ofDays(7)));
    return new GenerationRunReader.SavedRun(
        saved.runId(),
        saved.tripId(),
        saved.targetDayId(),
        saved.baseScheduleVersionId(),
        saved.status(),
        outcome,
        saved.commandInputHash(),
        saved.createdAt(),
        saved.startedAt(),
        saved.completedAt(),
        saved.retainedUntil(),
        saved.factsAsOf(),
        saved.stale(),
        saved.failure(),
        candidates);
  }

  @Test
  void 타인과_없는_작업은_만료정보를_노출하지_않고_같은_404코드를_사용한다() {
    when(reader.findOwned(owner, trip, run)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.read(owner, trip, run)).hasMessage("ASYNC_RUN_NOT_FOUND");
  }

  @Test
  void 종료작업은_7일_보존_경계부터_만료이고_대기와_실행은_계속_조회한다() {
    for (String status : List.of("succeeded", "failed", "cancelled")) {
      var saved = saved(status, now.plusSeconds(1));
      when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(saved));
      assertThat(service.read(owner, trip, run)).isSameAs(saved);
      for (Instant expiry : List.of(now, now.minusSeconds(1))) {
        when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(saved(status, expiry)));
        assertThatThrownBy(() -> service.read(owner, trip, run)).hasMessage("ASYNC_RESULT_EXPIRED");
      }
    }
    for (String status : List.of("queued", "running")) {
      var saved = saved(status, null);
      when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(saved));
      assertThat(service.read(owner, trip, run)).isSameAs(saved);
    }
  }

  @Test
  void 종료상태의_보존시각이_없으면_무기한_결과로_노출하지_않는다() {
    when(reader.findOwned(owner, trip, run)).thenReturn(Optional.of(saved("failed", null)));
    assertThatThrownBy(() -> service.read(owner, trip, run))
        .hasMessage("ASYNC_RESULT_TEMPORARILY_UNAVAILABLE");
  }

  private GenerationRunReader.SavedRun saved(String status, Instant expiry) {
    return new GenerationRunReader.SavedRun(
        run,
        trip,
        UUID.randomUUID(),
        null,
        status,
        status.equals("succeeded") ? "insufficient_feasible_routes" : null,
        "a".repeat(64),
        now.minus(Duration.ofDays(8)),
        status.equals("queued") ? null : now.minus(Duration.ofDays(8)),
        expiry == null ? null : expiry.minus(Duration.ofDays(7)),
        expiry,
        status.equals("succeeded") && expiry != null ? expiry.minus(Duration.ofDays(7)) : null,
        false,
        GenerationFailure.from(status, null),
        List.of());
  }
}
