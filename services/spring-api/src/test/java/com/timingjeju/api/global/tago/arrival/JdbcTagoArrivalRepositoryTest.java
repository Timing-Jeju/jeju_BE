package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@Tag("unit")
class JdbcTagoArrivalRepositoryTest {
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");

  @Test
  void history_SQL_failure는_raw_message와_cause없이_DATA_UNAVAILABLE로_변환한다() {
    JdbcTemplate jdbc =
        new FailingJdbcTemplate(
            new DataAccessResourceFailureException("select password from secret_table"));
    JdbcTagoArrivalRepository repository = new JdbcTagoArrivalRepository(jdbc);

    assertThatThrownBy(() -> repository.findLatest(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getMessage()).doesNotContain("password", "select");
            });
  }

  @Test
  void history_row의_required_timestamp가_null이면_typed_DATA_UNAVAILABLE다() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getTimestamp("observed_at")).thenReturn(null);

    assertThatThrownBy(() -> JdbcTagoArrivalRepository.mapLineage(resultSet))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getCause()).isNull();
            });
  }

  @Test
  void history_arrival_row의_required_text가_null이면_typed_DATA_UNAVAILABLE다() throws Exception {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("external_route_id")).thenReturn(null);
    when(resultSet.getString("route_no")).thenReturn("201");
    when(resultSet.getString("route_type")).thenReturn("간선");
    when(resultSet.getString("vehicle_type")).thenReturn("일반");
    when(resultSet.getInt("estimated_arrival_seconds")).thenReturn(60);
    when(resultSet.getInt("remaining_stops")).thenReturn(1);

    assertThatThrownBy(() -> JdbcTagoArrivalRepository.mapArrival(resultSet))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getCause()).isNull();
            });
  }

  @Test
  void history의_programmer_bug는_DATA_UNAVAILABLE로_숨기지_않는다() {
    IllegalStateException programmerBug = new IllegalStateException("row serializer bug");
    JdbcTagoArrivalRepository repository =
        new JdbcTagoArrivalRepository(new FailingJdbcTemplate(programmerBug));

    assertThatThrownBy(() -> repository.findLatest(KEY)).isSameAs(programmerBug);
  }

  private static final class FailingJdbcTemplate extends JdbcTemplate {
    private final RuntimeException failure;

    private FailingJdbcTemplate(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      throw failure;
    }
  }
}
