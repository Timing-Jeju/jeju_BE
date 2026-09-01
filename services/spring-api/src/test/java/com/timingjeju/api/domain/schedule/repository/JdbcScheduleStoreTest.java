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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
  void JDBC_failure는_SQL과_credential을_버린_cause없는_data_unavailable이다() {
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
              assertThat(failure.code()).isEqualTo("TRIP_DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getStackTrace()).isEmpty();
              assertThat(failure.getMessage()).doesNotContain("password");
            });
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
}
