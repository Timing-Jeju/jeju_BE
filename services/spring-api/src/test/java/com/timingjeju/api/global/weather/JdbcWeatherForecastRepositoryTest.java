package com.timingjeju.api.global.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.exception.WeatherForecastDataUnavailableException;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class JdbcWeatherForecastRepositoryTest {

  @Test
  void SQL은_normalized_lifecycle만_읽고_raw_provider_columns를_선택하지_않는다() {
    assertThat(JdbcWeatherForecastRepository.SELECT_FORECAST)
        .contains(
            "snapshot.parse_status='parsed'",
            "import_run.status='succeeded'",
            "snapshot.import_run_id=f.import_run_id",
            "f.source_operation=:sourceOperation",
            "f.precipitation_intensity_code is null",
            "f.wind_strength_code is not null")
        .doesNotContain("raw_payload", "request_metadata_redacted", "error_message");
    assertThat(JdbcWeatherForecastRepository.SELECT_GRID).doesNotContain("representative_location");
  }

  @Test
  void grid_read_data_access_failure는_raw_cause없는_typed_error다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("select secret_table"));

    assertThatThrownBy(
            () ->
                new JdbcWeatherForecastRepository(jdbc).findSupportedGrid(new KmaGridPoint(60, 37)))
        .isInstanceOf(WeatherForecastDataUnavailableException.class)
        .hasMessage(null)
        .hasNoCause();
  }

  @Test
  void forecast_read_data_access_failure도_같은_typed_boundary다() {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("raw SQL"));
    WeatherForecastLookup lookup =
        new WeatherForecastLookup(
            new KmaGridPoint(60, 37),
            ForecastType.ULTRA_SHORT,
            new ForecastBaseTime(LocalDate.of(2026, 8, 3), LocalTime.of(13, 30)),
            Instant.parse("2026-08-03T06:00:00Z"));

    assertThatThrownBy(() -> new JdbcWeatherForecastRepository(jdbc).find(lookup))
        .isInstanceOf(WeatherForecastDataUnavailableException.class)
        .hasMessage(null)
        .hasNoCause();
  }
}
