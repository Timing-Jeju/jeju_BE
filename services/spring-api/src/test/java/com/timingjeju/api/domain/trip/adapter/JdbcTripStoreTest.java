package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.trip.CreateTripCommand;
import com.timingjeju.api.application.trip.CreateTripRecord;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripListCursor;
import com.timingjeju.api.application.trip.TripTransportMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcTripStoreTest {
  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("44000000-0000-0000-0000-000000000044");
  private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void findOwned는_tripId와_ownerId를_같은_SQL_predicate로_조회한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    assertThat(new JdbcTripStore(jdbc, named).findOwned(OWNER, TRIP, NOW)).isEmpty();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(named).query(sql.capture(), anyMap(), any(RowMapper.class));
    assertThat(sql.getValue()).contains("p.id = :tripId").contains("p.user_id = :ownerId");
  }

  @Test
  void listOwned는_owner_status와_desc_keyset을_SQL에서_제한한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenReturn(List.of());

    new JdbcTripStore(jdbc, named)
        .listOwned(OWNER, "planned", new TripListCursor(NOW.minusSeconds(1), TRIP), 21, NOW);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(named).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains("p.user_id = :ownerId")
        .contains("p.status = :status")
        .contains("p.updated_at < :afterUpdatedAt")
        .contains("p.id < :afterTripId")
        .contains("order by p.updated_at desc, p.id desc");
  }

  @Test
  void findOwned와_listOwned의_DAO_failure는_cause없는_data_unavailable로_변환한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    DataAccessResourceFailureException detailFailure =
        new DataAccessResourceFailureException("jdbc:postgresql://private-user:password@db/trips");
    when(named.query(anyString(), anyMap(), any(RowMapper.class))).thenThrow(detailFailure);
    JdbcTripStore store = new JdbcTripStore(jdbc, named);

    assertCauseFreeDataUnavailable(() -> store.findOwned(OWNER, TRIP, NOW), detailFailure);

    DataAccessResourceFailureException listFailure =
        new DataAccessResourceFailureException("select secret_token from private_trip_table");
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(listFailure);

    assertCauseFreeDataUnavailable(() -> store.listOwned(OWNER, null, null, 21, NOW), listFailure);
  }

  @Test
  void score는_active_schedule의_latest_successful_feasibility_run에서만_읽는다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), anyMap(), any(RowMapper.class))).thenReturn(List.of());

    new JdbcTripStore(jdbc, named).findOwned(OWNER, TRIP, NOW);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(named).query(sql.capture(), anyMap(), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains("r.schedule_version_id = p.active_schedule_version_id")
        .contains("r.run_type = 'feasibility'")
        .contains("r.status = 'succeeded'")
        .contains("order by r.completed_at desc nulls last, r.id desc")
        .contains("jsonb_typeof(score_run.result_summary -> 'score') as score_type")
        .contains("jsonb_exists(score_run.result_summary, 'observedAt') as score_observed_present")
        .doesNotContain("score_run.result_summary ? 'observedAt'")
        .contains("jsonb_typeof(score_run.result_summary -> 'observedAt')")
        .contains("score_run.facts_snapshot_at as score_facts_observed_at")
        .doesNotContain("p.total_score");
    assertThatCode(
            () ->
                NamedParameterUtils.buildValueArray(
                    NamedParameterUtils.parseSqlStatement(sql.getValue()),
                    new MapSqlParameterSource().addValue("tripId", TRIP).addValue("ownerId", OWNER),
                    null))
        .as("PostgreSQL JSON 연산자가 JDBC positional parameter로 파싱되면 안 된다")
        .doesNotThrowAnyException();
  }

  @Test
  void testOnly_진단은_public_예외를_바꾸지_않고_root_SQL_stage만_cause없이_노출한다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenThrow(
            new BadSqlGrammarException(
                "query", "redacted", new java.sql.SQLException("redacted", "42P01")));
    JdbcTripStore store = new JdbcTripStore(jdbc, named);

    assertThatThrownBy(() -> store.findOwnedDiagnosticForTest(OWNER, TRIP, NOW))
        .isInstanceOf(AssertionError.class)
        .hasMessage("TRIP_JDBC_STAGE:ROOT_QUERY:BadSqlGrammarException:42P01")
        .hasNoCause();
    assertThatThrownBy(() -> store.findOwned(OWNER, TRIP, NOW))
        .isInstanceOf(TripException.class)
        .hasNoCause();
  }

  @Test
  void day_insert_실패는_도메인_constraint_error로_변환되고_create는_쓰기_transaction이다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.batchUpdate(anyString(), anyList()))
        .thenReturn(new int[] {1})
        .thenThrow(new DataIntegrityViolationException("test-only"));
    JdbcTripStore store = new JdbcTripStore(jdbc, named);

    assertThatThrownBy(() -> store.create(record()))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_CONSTRAINT_VIOLATION");
    Transactional boundary =
        JdbcTripStore.class
            .getMethod("create", CreateTripRecord.class)
            .getAnnotation(Transactional.class);
    assertThat(boundary).isNotNull();
    assertThat(boundary.readOnly()).isFalse();
  }

  private static CreateTripRecord record() {
    return new CreateTripRecord(
        OWNER,
        TRIP,
        "44000000-0000-0000-0000-000000000099",
        new CreateTripCommand(
            "제주 여행",
            LocalDate.parse("2026-08-03"),
            LocalDate.parse("2026-08-05"),
            "Asia/Seoul",
            "normal",
            List.of(new TripTransportMode("public_transit", 1, true))),
        List.of(
            UUID.fromString("44000000-0000-0000-0001-000000000001"),
            UUID.fromString("44000000-0000-0000-0001-000000000002"),
            UUID.fromString("44000000-0000-0000-0001-000000000003")),
        NOW);
  }

  private static void assertCauseFreeDataUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
      RuntimeException rawFailure) {
    assertThatThrownBy(operation)
        .isInstanceOfSatisfying(
            TripException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("TRIP_DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getStackTrace()).isEmpty();
              assertThat(failure.getMessage()).doesNotContain(rawFailure.getMessage());
            });
  }
}
