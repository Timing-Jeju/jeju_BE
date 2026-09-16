package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.domain.schedule.adapter.JdbcScheduleStore;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcScheduleStoreTest {
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000201");
  private static final UUID TRIP = UUID.fromString("49000000-0000-0000-0000-000000000202");
  private static final UUID VERSION = UUID.fromString("49000000-0000-0000-0000-000000000203");
  private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

  @ParameterizedTest
  @ValueSource(strings = {"candidate", "rejected", "draft", "active", "superseded"})
  void 적용_증거없는_AI_버전은_status를_바꿔도_후보_만료를_우회하지_못한다(String state) throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var named = mock(NamedParameterJdbcTemplate.class);
    var root = rootResultSet();
    when(root.getString("version_status")).thenReturn(state);
    when(root.getString("source_type")).thenReturn("ai_generation");
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              if (invocation.<String>getArgument(0).contains("as expired")) return List.of(true);
              return List.of();
            });
    assertThatThrownBy(
            () -> new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, VERSION, NOW))
        .hasMessage("CANDIDATE_EXPIRED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"정상", "다른장소", "미상거리", "요금", "버퍼", "택시", "도보시간", "도착시각"})
  void 영분_이동은_동일장소의_영비용_위치연속성만_조회한다(String scenario) throws Exception {
    UUID day = UUID.randomUUID();
    UUID from = UUID.randomUUID();
    UUID to = UUID.randomUUID();
    UUID place = UUID.randomUUID();
    ResultSet first = itemResultSet(from, day, 1, "첫 방문");
    ResultSet second = itemResultSet(to, day, 2, "다음 방문");
    when(first.getObject("place_id", UUID.class)).thenReturn(place);
    when(second.getObject("place_id", UUID.class)).thenReturn(place);
    when(second.getTimestamp("planned_start_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")));
    when(second.getTimestamp("planned_end_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T02:00:00Z")));
    ResultSet leg = legResultSet(UUID.randomUUID(), day, from, to);
    when(leg.getTimestamp("planned_arrival_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")));
    when(leg.getObject("duration_minutes")).thenReturn(0);
    when(leg.getObject("walk_minutes")).thenReturn(0);
    when(leg.getObject("distance_meters")).thenReturn(0);
    when(leg.getObject("estimated_fare")).thenReturn(0);
    switch (scenario) {
      case "정상" -> {}
      case "다른장소" -> when(second.getObject("place_id", UUID.class)).thenReturn(UUID.randomUUID());
      case "미상거리" -> when(leg.getObject("distance_meters")).thenReturn(null);
      case "요금" -> when(leg.getObject("estimated_fare")).thenReturn(1);
      case "버퍼" -> when(leg.getObject("buffer_minutes")).thenReturn(1);
      case "택시" -> when(leg.getString("transport_mode")).thenReturn("taxi");
      case "도보시간" -> when(leg.getObject("walk_minutes")).thenReturn(1);
      case "도착시각" ->
          when(leg.getTimestamp("planned_arrival_at"))
              .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:00:30Z")));
      default -> throw new AssertionError("알 수 없는 시나리오");
    }
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = rootResultSet();
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    var rows =
        List.of(List.of(dayResultSet(day, 1, "2026-09-01")), List.of(first, second), List.of(leg));
    AtomicInteger queryIndex = new AtomicInteger();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              var mapped = new java.util.ArrayList<>();
              RowMapper<?> mapper = invocation.getArgument(1);
              for (ResultSet row : rows.get(queryIndex.getAndIncrement()))
                mapped.add(mapper.mapRow(row, mapped.size()));
              return mapped;
            });
    var store = new JdbcScheduleStore(jdbc, named);
    if (scenario.equals("정상")) {
      assertThat(
              store
                  .readOwned(OWNER, TRIP, VERSION, NOW)
                  .schedule()
                  .days()
                  .getFirst()
                  .legs()
                  .getFirst()
                  .durationMinutes())
          .isZero();
    } else {
      assertThatThrownBy(() -> store.readOwned(OWNER, TRIP, VERSION, NOW))
          .hasMessage("INTERNAL_SERVER_ERROR");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"표식없음", "알수없는역할", "장소없음", "방문유형", "종료불일치", "체류양수", "버퍼양수"})
  void 잘못된_영분_기준점은_일정조회에서_거부한다(String scenario) throws Exception {
    UUID day = UUID.randomUUID();
    ResultSet item = itemResultSet(UUID.randomUUID(), day, 1, "시작 기준점");
    when(item.getString("boundary_role")).thenReturn("day_start");
    when(item.getObject("place_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(item.getObject("stay_minutes")).thenReturn(0);
    when(item.getTimestamp("planned_end_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
    switch (scenario) {
      case "표식없음" -> when(item.getString("boundary_role")).thenReturn(null);
      case "알수없는역할" -> when(item.getString("boundary_role")).thenReturn("unknown");
      case "장소없음" -> when(item.getObject("place_id", UUID.class)).thenReturn(null);
      case "방문유형" -> when(item.getString("item_type")).thenReturn("place_visit");
      case "종료불일치" ->
          when(item.getTimestamp("planned_end_at"))
              .thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:01:00Z")));
      case "체류양수" -> when(item.getObject("stay_minutes")).thenReturn(1);
      case "버퍼양수" -> when(item.getObject("buffer_after_minutes")).thenReturn(1);
      default -> throw new AssertionError("알 수 없는 검증 시나리오");
    }
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = rootResultSet();
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    ResultSet dayRow = dayResultSet(day, 1, "2026-09-01");
    AtomicInteger queryIndex = new AtomicInteger();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation ->
                switch (queryIndex.getAndIncrement()) {
                  case 0 -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(dayRow, 0));
                  case 1 -> List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(item, 0));
                  case 2 -> List.of();
                  default -> throw new AssertionError("예상하지 않은 JDBC query입니다.");
                });
    assertThatThrownBy(
            () -> new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, VERSION, NOW))
        .isInstanceOfSatisfying(
            ScheduleException.class,
            failure -> assertThat(failure.code()).isEqualTo("INTERNAL_SERVER_ERROR"));
  }

  @Test
  void 발견된_일정은_day수와_무관하게_root_days_items_legs_네_query만_사용한다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = rootResultSet();
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    var lookup = new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, VERSION, NOW);

    assertThat(lookup.schedule().scheduleVersion().scheduleVersionId()).isEqualTo(VERSION);
    verify(named, times(1))
        .query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    verify(jdbc, times(3)).query(anyString(), any(RowMapper.class), any(Object[].class));
    ArgumentCaptor<String> rootSql = ArgumentCaptor.forClass(String.class);
    verify(named).query(rootSql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
    assertThat(rootSql.getValue())
        .contains("p.id = :tripId and p.user_id = :ownerId")
        .contains("v.id = :versionId")
        .contains("r.schedule_version_id = v.id")
        .contains("r.run_type = 'feasibility'")
        .contains("r.status = 'succeeded'");
  }

  @Test
  void readOwned는_repeatable_read의_read_only_transaction이다() throws Exception {
    Transactional boundary =
        JdbcScheduleStore.class
            .getMethod("readOwned", UUID.class, UUID.class, UUID.class, Instant.class)
            .getAnnotation(Transactional.class);

    assertThat(boundary).isNotNull();
    assertThat(boundary.readOnly()).isTrue();
    assertThat(boundary.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
  }

  @Test
  void malformed_freshness는_신규_DB_write없이_legacy_read_boundary에서_stale이다() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = rootResultSet();
    when(root.getObject("resulting_score")).thenReturn(81);
    when(root.getTimestamp("freshness_calculated_at")).thenReturn(Timestamp.from(NOW));
    when(root.getTimestamp("freshness_facts_observed_at"))
        .thenReturn(Timestamp.from(NOW.minusSeconds(60)));
    when(root.getBoolean("freshness_observed_present")).thenReturn(true);
    when(root.getString("freshness_observed_type")).thenReturn("string");
    when(root.getString("freshness_observed_value")).thenReturn("bad");
    when(root.getString("freshness_expires_type")).thenReturn("string");
    when(root.getString("freshness_expires_value")).thenReturn(NOW.plusSeconds(60).toString());
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

    var schedule =
        new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, VERSION, NOW).schedule();

    assertThat(schedule.scheduleVersion().score()).isEqualTo(81);
    assertThat(schedule.scheduleVersion().feasibilityStale()).isTrue();
  }

  @Test
  void JDBC_failure는_SQL과_credential을_버린_cause없는_internal_server_error이다() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(
            new DataAccessResourceFailureException(
                "jdbc:postgresql://private-user:password@db/schedules"));

    assertThatThrownBy(() -> new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, null, NOW))
        .isInstanceOfSatisfying(
            ScheduleException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo("INTERNAL_SERVER_ERROR");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getStackTrace()).isEmpty();
              assertThat(failure.getMessage()).doesNotContain("password");
            });
  }

  @Test
  void 중복_day_item_leg_sequence는_UUID_tie_break로_모든_행을_안정_투영한다() throws Exception {
    UUID firstDay = UUID.fromString("49000000-0000-0000-0000-000000000211");
    UUID secondDay = UUID.fromString("49000000-0000-0000-0000-000000000212");
    UUID firstItem = UUID.fromString("49000000-0000-0000-0000-000000000221");
    UUID secondItem = UUID.fromString("49000000-0000-0000-0000-000000000222");
    UUID thirdItem = UUID.fromString("49000000-0000-0000-0000-000000000223");
    UUID otherDayItem = UUID.fromString("49000000-0000-0000-0000-000000000224");
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = rootResultSet();
    when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    List<ResultSet> days =
        List.of(dayResultSet(firstDay, 1, "2026-09-01"), dayResultSet(secondDay, 1, "2026-09-01"));
    List<ResultSet> items =
        List.of(
            itemResultSet(firstItem, firstDay, 1, "첫 항목"),
            itemResultSet(secondItem, firstDay, 1, "둘째 항목"),
            itemResultSet(thirdItem, firstDay, 1, "셋째 항목"),
            itemResultSet(otherDayItem, secondDay, 1, "다른 Day 항목"));
    List<ResultSet> legs =
        List.of(
            legResultSet(
                UUID.fromString("49000000-0000-0000-0000-000000000231"),
                firstDay,
                firstItem,
                secondItem),
            legResultSet(
                UUID.fromString("49000000-0000-0000-0000-000000000232"),
                firstDay,
                secondItem,
                thirdItem));
    AtomicInteger queryIndex = new AtomicInteger();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              RowMapper<?> mapper = invocation.getArgument(1);
              List<ResultSet> rows =
                  switch (queryIndex.getAndIncrement()) {
                    case 0 -> days;
                    case 1 -> items;
                    case 2 -> legs;
                    default -> throw new AssertionError("예상하지 않은 JDBC query입니다.");
                  };
              java.util.ArrayList<Object> mapped = new java.util.ArrayList<>();
              for (int index = 0; index < rows.size(); index++) {
                mapped.add(mapper.mapRow(rows.get(index), index));
              }
              return mapped;
            });

    var schedule =
        new JdbcScheduleStore(jdbc, named).readOwned(OWNER, TRIP, VERSION, NOW).schedule();

    assertThat(schedule.days()).extracting(day -> day.dayId()).containsExactly(firstDay, secondDay);
    assertThat(schedule.days()).extracting(day -> day.dayNo()).containsExactly(1, 1);
    assertThat(schedule.days().getFirst().items())
        .extracting(item -> item.itemId())
        .containsExactly(firstItem, secondItem, thirdItem);
    assertThat(schedule.days().getFirst().items())
        .extracting(item -> item.sequenceNo())
        .containsExactly(1, 1, 1);
    assertThat(schedule.days().getFirst().legs())
        .extracting(leg -> leg.sequenceNo())
        .containsExactly(1, 1);
    assertThat(schedule.days().getFirst().legs().getFirst().fromItemId()).isEqualTo(firstItem);
    assertThat(schedule.days().getFirst().legs().getFirst().toItemId()).isEqualTo(secondItem);
    assertThat(schedule.days().getFirst().legs().get(1).fromItemId()).isEqualTo(secondItem);
    assertThat(schedule.days().getFirst().legs().get(1).toItemId()).isEqualTo(thirdItem);
  }

  private static ResultSet rootResultSet() throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject("trip_id", UUID.class)).thenReturn(TRIP);
    when(result.getObject("version_id", UUID.class)).thenReturn(VERSION);
    when(result.getObject("version_no")).thenReturn(1);
    when(result.getString("version_status")).thenReturn("candidate");
    when(result.getString("source_type")).thenReturn("initial");
    when(result.getObject("base_schedule_version_id", UUID.class)).thenReturn(null);
    when(result.getObject("resulting_score")).thenReturn(null);
    when(result.getBoolean("freshness_observed_present")).thenReturn(false);
    return result;
  }

  private static ResultSet dayResultSet(UUID id, int dayNo, String date) throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject("id", UUID.class)).thenReturn(id);
    when(result.getObject("day_no")).thenReturn(dayNo);
    when(result.getDate("trip_date")).thenReturn(java.sql.Date.valueOf(LocalDate.parse(date)));
    return result;
  }

  private static ResultSet itemResultSet(UUID id, UUID dayId, int sequenceNo, String title)
      throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject("id", UUID.class)).thenReturn(id);
    when(result.getObject("trip_day_id", UUID.class)).thenReturn(dayId);
    when(result.getObject("sequence_no")).thenReturn(sequenceNo);
    when(result.getString("item_type")).thenReturn("custom");
    when(result.getString("projection_title")).thenReturn(title);
    when(result.getTimestamp("planned_start_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
    when(result.getTimestamp("planned_end_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")));
    when(result.getObject("stay_minutes")).thenReturn(60);
    when(result.getObject("buffer_after_minutes")).thenReturn(0);
    return result;
  }

  private static ResultSet legResultSet(UUID id, UUID dayId, UUID fromItemId, UUID toItemId)
      throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject("id", UUID.class)).thenReturn(id);
    when(result.getObject("trip_day_id", UUID.class)).thenReturn(dayId);
    when(result.getObject("sequence_no")).thenReturn(1);
    when(result.getObject("from_item_id", UUID.class)).thenReturn(fromItemId);
    when(result.getObject("to_item_id", UUID.class)).thenReturn(toItemId);
    when(result.getString("transport_mode")).thenReturn("walk");
    when(result.getTimestamp("planned_departure_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")));
    when(result.getTimestamp("planned_arrival_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T01:10:00Z")));
    when(result.getObject("walk_minutes")).thenReturn(10);
    when(result.getObject("wait_minutes")).thenReturn(0);
    when(result.getObject("ride_minutes")).thenReturn(0);
    when(result.getObject("transfer_minutes")).thenReturn(0);
    when(result.getObject("duration_minutes")).thenReturn(10);
    when(result.getObject("buffer_minutes")).thenReturn(0);
    return result;
  }
}
