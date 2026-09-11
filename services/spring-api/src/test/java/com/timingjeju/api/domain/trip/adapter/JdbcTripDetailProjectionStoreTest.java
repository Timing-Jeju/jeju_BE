package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripTransportMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
@SuppressWarnings("unchecked")
class JdbcTripDetailProjectionStoreTest {
  private static final UUID ID = UUID.fromString("24600000-0000-0000-0000-000000000201");
  private static final Instant NOW = Instant.parse("2026-09-01T00:30:00Z");

  @Test
  void 하위_행을_두_SQL로_읽고_터미널과_숙소_값을_정확히_매핑한다() throws Exception {
    var fixture = fixture("숙소");
    var result = fixture.store().findOwned(ID, ID, NOW).orElseThrow();
    assertThat(result.accommodations()).hasSize(1);
    assertThat(result.accommodations().getFirst().name()).isEqualTo("숙소");
    assertThat(result.accommodations().getFirst().checkInTime().toString()).isEqualTo("15:00");
    assertThat(result.transportEvents().arrival().scheduledAt().toString())
        .isEqualTo("2026-09-01T09:30+09:00");
    assertThat(result.transportEvents().arrival().customTerminalName()).isEqualTo("입력 터미널");
    assertThat(result.transportEvents().departure()).isNull();
    assertThat(fixture.childSql()).hasSize(2);
    assertThat(fixture.childSql()).allSatisfy(sql -> assertThat(sql).contains("trip_plan_id = ?"));
    assertThat(
            fixture.childSql().stream()
                .filter(sql -> sql.contains("public.trip_accommodations"))
                .findFirst()
                .orElseThrow())
        .contains("order by")
        .contains("sequence_no");
  }

  @Test
  void 손상된_숙소_표시값은_다른_도메인_예외나_부분응답_없이_거부한다() throws Exception {
    var fixture = fixture("\u1100\u1161");
    assertThatThrownBy(() -> fixture.store().findOwned(ID, ID, NOW))
        .isInstanceOf(TripException.class)
        .hasNoCause()
        .extracting(error -> ((TripException) error).code())
        .isEqualTo("TRIP_DATA_UNAVAILABLE");
  }

  private static Fixture fixture(String name) throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    ResultSet root = row(name);
    when(root.getLong("revision")).thenReturn(1L);
    when(named.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(invocation -> List.of(invocation.<RowMapper<?>>getArgument(2).mapRow(root, 0)));
    List<String> childSql = new ArrayList<>();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("public.trip_transport_modes"))
                return List.of(new TripTransportMode("public_transit", 1, true));
              if (sql.contains("public.trip_days"))
                return List.of(new TripDay(ID, 1, LocalDate.of(2026, 9, 1)));
              childSql.add(sql);
              assertThat(invocation.<UUID>getArgument(2)).isEqualTo(ID);
              return List.of(invocation.<RowMapper<?>>getArgument(1).mapRow(row(name), 0));
            });
    return new Fixture(new JdbcTripStore(jdbc, named), childSql);
  }

  private static ResultSet row(String name) throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getObject("id", UUID.class)).thenReturn(ID);
    when(row.getDate(anyString())).thenReturn(Date.valueOf("2026-09-01"));
    when(row.getDate("end_date")).thenReturn(Date.valueOf("2026-09-02"));
    when(row.getDate("check_out_date")).thenReturn(Date.valueOf("2026-09-02"));
    when(row.getTimestamp(anyString())).thenReturn(Timestamp.from(NOW));
    when(row.getTime("check_in_time")).thenReturn(Time.valueOf("15:00:00"));
    when(row.getTime("check_out_time")).thenReturn(Time.valueOf("11:00:00"));
    when(row.getInt("sequence_no")).thenReturn(1);
    when(row.getString(anyString()))
        .thenAnswer(
            invocation ->
                switch (invocation.<String>getArgument(0)) {
                  case "title" -> "여행";
                  case "status" -> "draft";
                  case "timezone" -> "Asia/Seoul";
                  case "user_pace" -> "normal";
                  case "name", "custom_name" -> name;
                  case "event_type" -> "arrival";
                  case "transport_type" -> "flight";
                  case "terminal_name" -> "입력 터미널";
                  default -> null;
                });
    return row;
  }

  private record Fixture(JdbcTripStore store, List<String> childSql) {}
}
