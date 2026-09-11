package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.trip.TripAggregateMutationCommit;
import com.timingjeju.api.application.trip.TripAggregateMutationPlan;
import com.timingjeju.api.application.trip.TripAggregateMutationState;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripRootPatch;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcTripAggregateMutationCoordinatorTest {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000901");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000902");
  private static final UUID ACTIVE = UUID.fromString("68000000-0000-0000-0000-000000000903");
  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void noChange는_root_CAS와_schedule_effect없이_revision과_active를_보존한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = named(state("planned", ACTIVE, 7));

    TripAggregateMutationCommit<String> commit =
        new JdbcTripAggregateMutationCoordinator(jdbc, named)
            .execute(OWNER, TRIP, 7, NOW, ignored -> TripAggregateMutationPlan.noChange("same"));

    assertThat(commit.payload()).isEqualTo("same");
    assertThat(commit.revision()).isEqualTo(7);
    assertThat(commit.activeScheduleVersionId()).isEqualTo(ACTIVE);
    assertThat(commit.scheduleEffect()).isEqualTo("none");
    verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    verifyNoInteractions(jdbc);
  }

  @Test
  void executeMonotonic은_root_lock뒤_계산한_timestamp를_operation과_root_CAS에_같이_전달한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = named(state("draft", null, 7));
    Instant requestedAt = NOW.minusSeconds(60);
    Instant expectedAt = NOW.plusNanos(1_000);
    AtomicReference<Instant> operationAt = new AtomicReference<>();
    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
        .thenReturn(Timestamp.from(NOW));
    when(named.update(anyString(), any(MapSqlParameterSource.class)))
        .thenAnswer(
            invocation -> {
              MapSqlParameterSource parameters = invocation.getArgument(1);
              assertThat(parameters.getValue("updatedAt")).isEqualTo(Timestamp.from(expectedAt));
              return 1;
            });

    new JdbcTripAggregateMutationCoordinator(jdbc, named)
        .executeMonotonic(
            OWNER,
            TRIP,
            7,
            requestedAt,
            (ignored, committedAt) -> {
              operationAt.set(committedAt);
              return TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), "changed");
            });

    assertThat(operationAt).hasValue(expectedAt);
  }

  @Test
  void active_invalidate는_beforeRoot_schedule_supersede_root_CAS_effect_순서를_보장한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = named(state("planned", ACTIVE, 7));
    AtomicInteger order = new AtomicInteger();
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              assertThat(order.incrementAndGet()).isEqualTo(2);
              return 1;
            });
    when(named.update(anyString(), any(MapSqlParameterSource.class)))
        .thenAnswer(
            invocation -> {
              assertThat(order.incrementAndGet()).isEqualTo(3);
              return 1;
            });

    TripAggregateMutationCommit<String> commit =
        new JdbcTripAggregateMutationCoordinator(jdbc, named)
            .execute(
                OWNER,
                TRIP,
                7,
                NOW,
                ignored ->
                    TripAggregateMutationPlan.invalidate(
                        TripRootPatch.unchanged(),
                        () -> assertThat(order.incrementAndGet()).isEqualTo(1),
                        () -> assertThat(order.incrementAndGet()).isEqualTo(4),
                        "changed"));

    assertThat(commit.revision()).isEqualTo(8);
    assertThat(commit.status()).isEqualTo("draft");
    assertThat(commit.activeScheduleVersionId()).isNull();
    assertThat(commit.scheduleEffect()).isEqualTo("invalidated");
    assertThat(order).hasValue(4);
  }

  @Test
  void active가_없는_invalidate와_maintain은_schedule_row를_건드리지_않는다() {
    JdbcTemplate invalidateJdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate invalidateNamed = named(state("planned", null, 7));
    when(invalidateNamed.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

    TripAggregateMutationCommit<String> invalidated =
        new JdbcTripAggregateMutationCoordinator(invalidateJdbc, invalidateNamed)
            .execute(
                OWNER,
                TRIP,
                7,
                NOW,
                ignored ->
                    TripAggregateMutationPlan.invalidate(TripRootPatch.unchanged(), "changed"));

    assertThat(invalidated.scheduleEffect()).isEqualTo("invalidated");
    verifyNoInteractions(invalidateJdbc);

    JdbcTemplate maintainJdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate maintainNamed = named(state("draft", null, 7));
    when(maintainNamed.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
    TripAggregateMutationCommit<String> maintained =
        new JdbcTripAggregateMutationCoordinator(maintainJdbc, maintainNamed)
            .execute(
                OWNER,
                TRIP,
                7,
                NOW,
                ignored ->
                    TripAggregateMutationPlan.maintain(TripRootPatch.unchanged(), "changed"));

    assertThat(maintained.scheduleEffect()).isEqualTo("maintained");
    assertThat(maintained.status()).isEqualTo("draft");
    verifyNoInteractions(maintainJdbc);
  }

  @Test
  void stale와_terminal은_operation과_write전에_common_TripException으로_중단한다() {
    for (TripAggregateMutationState state :
        List.of(state("planned", null, 8), state("completed", null, 7))) {
      JdbcTemplate jdbc = mock(JdbcTemplate.class);
      NamedParameterJdbcTemplate named = named(state);
      AtomicBoolean called = new AtomicBoolean();

      assertThatThrownBy(
              () ->
                  new JdbcTripAggregateMutationCoordinator(jdbc, named)
                      .execute(
                          OWNER,
                          TRIP,
                          7,
                          NOW,
                          ignored -> {
                            called.set(true);
                            return TripAggregateMutationPlan.noChange("never");
                          }))
          .isInstanceOf(TripException.class)
          .extracting(failure -> ((TripException) failure).code())
          .isIn("TRIP_VERSION_CONFLICT", "TRIP_TERMINAL_STATE_CONFLICT");
      assertThat(called).isFalse();
      verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
      verifyNoInteractions(jdbc);
    }
  }

  @Test
  void root_CAS실패는_after_effect를_실행하지_않고_transaction_rollback경계를_유지한다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = named(state("draft", null, 7));
    when(named.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
    AtomicBoolean before = new AtomicBoolean();
    AtomicBoolean after = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                new JdbcTripAggregateMutationCoordinator(jdbc, named)
                    .execute(
                        OWNER,
                        TRIP,
                        7,
                        NOW,
                        ignored ->
                            TripAggregateMutationPlan.maintain(
                                TripRootPatch.unchanged(),
                                () -> before.set(true),
                                () -> after.set(true),
                                "changed")))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_VERSION_CONFLICT");
    assertThat(before).isTrue();
    assertThat(after).isFalse();
    assertThat(
            JdbcTripAggregateMutationCoordinator.class
                .getMethod(
                    "execute",
                    UUID.class,
                    UUID.class,
                    long.class,
                    Instant.class,
                    com.timingjeju.api.application.trip.TripAggregateMutationOperation.class)
                .getAnnotation(Transactional.class))
        .isNotNull();
  }

  private static NamedParameterJdbcTemplate named(TripAggregateMutationState state) {
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of(state));
    return named;
  }

  private static TripAggregateMutationState state(String status, UUID active, long revision) {
    return new TripAggregateMutationState(
        status,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-05"),
        "Asia/Seoul",
        active,
        revision,
        active != null);
  }
}
