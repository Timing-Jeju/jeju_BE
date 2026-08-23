package com.timingjeju.api.global.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastBaseTimeResolver;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridConverter;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.dto.request.WeatherForecastQuery;
import com.timingjeju.api.domain.weather.dto.response.WeatherForecastResponse;
import com.timingjeju.api.domain.weather.exception.WeatherForecastDataUnavailableException;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.repository.WeatherForecastRepository;
import com.timingjeju.api.domain.weather.service.WeatherForecastQueryService;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcWeatherForecastRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID GRID = UUID.fromString("67000000-0000-0000-0000-000000000001");
  private static final KmaGridPoint GRID_POINT = new KmaGridPoint(60, 37);
  private static final Instant NOW = Instant.parse("2026-08-03T05:20:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

  @Autowired private WeatherForecastRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update(
        "insert into public.weather_grid_points(id,grid_provider,nx,ny,region_name) values (?,'KMA',60,37,'서귀포시 성산읍')",
        GRID);
  }

  @Test
  void exact_KMA_grid만_지원하고_정밀_요청좌표는_저장하지_않는다() {
    assertThat(repository.findSupportedGrid(GRID_POINT))
        .get()
        .extracting("regionName")
        .isEqualTo("서귀포시 성산읍");
    assertThat(repository.findSupportedGrid(new KmaGridPoint(59, 37))).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select representative_location is null from public.weather_grid_points where id=?",
                Boolean.class,
                GRID))
        .isTrue();
  }

  @Test
  void succeeded_parsed_snapshot의_exact_base_validAt과_normalized_categories만_읽는다() {
    ForecastBaseTime base = base(13, 30);
    Instant validAt = Instant.parse("2026-08-03T06:00:00Z");
    insertForecast(1, ForecastType.ULTRA_SHORT, base, validAt, NOW.minusSeconds(300));

    var row = repository.find(lookup(ForecastType.ULTRA_SHORT, base, validAt)).orElseThrow();

    assertThat(row.temperatureC()).isEqualByComparingTo("27.5");
    assertThat(row.precipitationType()).isEqualTo("0");
    assertThat(row.observedAt()).isEqualTo(NOW.minusSeconds(300));
    assertThat(row.expiresAt()).isEqualTo(NOW.plusSeconds(300));
  }

  @Test
  void current가_없으면_직전_base_한개만_조회해_fallback_stale을_반환한다() {
    insertForecast(
        2,
        ForecastType.ULTRA_SHORT,
        base(12, 30),
        Instant.parse("2026-08-03T06:00:00Z"),
        NOW.minusSeconds(60));

    WeatherForecastResponse response = service().forecast(query("2026-08-03T15:00:00+09:00"));

    assertThat(response.baseTime()).isEqualTo(LocalTime.of(12, 30));
    assertThat(response.fallbackUsed()).isTrue();
    assertThat(response.stale()).isTrue();
  }

  @Test
  void ultra_short_expiry와_response_evaluation이_같으면_stale이다() {
    insertForecast(
        3,
        ForecastType.ULTRA_SHORT,
        base(13, 30),
        Instant.parse("2026-08-03T06:00:00Z"),
        NOW.minusSeconds(600));

    WeatherForecastResponse response = service().forecast(query("2026-08-03T15:00:00+09:00"));

    assertThat(response.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-08-03T14:20:00+09:00"));
    assertThat(response.stale()).isTrue();
    assertThat(response.fallbackUsed()).isFalse();
  }

  @Test
  void short_storage는_village로_투영하고_expiry는_다음_base_eligible_시각이다() {
    ForecastBaseTime base = base(14, 0);
    insertForecast(
        4, ForecastType.VILLAGE, base, Instant.parse("2026-08-03T15:00:00Z"), NOW.minusSeconds(60));

    WeatherForecastResponse response = service().forecast(query("2026-08-04T00:00:00+09:00"));

    assertThat(response.forecastType()).isEqualTo("village");
    assertThat(response.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-08-03T17:10:00+09:00"));
    assertThat(response.skyCode()).isEqualTo("mostly_cloudy");
    assertThat(response.precipitationProbabilityPercent()).isEqualTo(40);
  }

  @Test
  void 다른_operation이나_lifecycle은_exact_lookup에서_사용하지_않는다() {
    ForecastBaseTime base = base(13, 30);
    Instant validAt = Instant.parse("2026-08-03T06:00:00Z");
    insertForecast(
        5, ForecastType.ULTRA_SHORT, base, validAt, NOW.minusSeconds(60), "getUltraSrtNcst");

    assertThat(repository.find(lookup(ForecastType.ULTRA_SHORT, base, validAt))).isEmpty();
  }

  @Test
  void JDBC_read_failure는_typed_boundary이고_raw_SQL_message를_노출하지_않는다() {
    jdbc.execute("alter table public.weather_forecasts rename to weather_forecasts_unavailable");

    assertThatThrownBy(
            () ->
                repository.find(
                    lookup(
                        ForecastType.ULTRA_SHORT,
                        base(13, 30),
                        Instant.parse("2026-08-03T06:00:00Z"))))
        .isInstanceOf(WeatherForecastDataUnavailableException.class)
        .hasMessage(null);
  }

  private WeatherForecastQueryService service() {
    return new WeatherForecastQueryService(
        repository, new KmaGridConverter(), new ForecastBaseTimeResolver(), CLOCK);
  }

  private static WeatherForecastQuery query(String dateTime) {
    return WeatherForecastQuery.of(33.458111, 126.941516, OffsetDateTime.parse(dateTime));
  }

  private static WeatherForecastLookup lookup(
      ForecastType type, ForecastBaseTime base, Instant validAt) {
    return new WeatherForecastLookup(GRID_POINT, type, base, validAt);
  }

  private static ForecastBaseTime base(int hour, int minute) {
    return new ForecastBaseTime(LocalDate.of(2026, 8, 3), LocalTime.of(hour, minute));
  }

  private void insertForecast(
      int sequence, ForecastType type, ForecastBaseTime base, Instant validAt, Instant fetchedAt) {
    insertForecast(
        sequence,
        type,
        base,
        validAt,
        fetchedAt,
        type == ForecastType.ULTRA_SHORT ? "getUltraSrtFcst" : "getVilageFcst");
  }

  private void insertForecast(
      int sequence,
      ForecastType type,
      ForecastBaseTime base,
      Instant validAt,
      Instant fetchedAt,
      String operation) {
    UUID run = UUID.fromString("67000000-0000-0000-0001-%012d".formatted(sequence));
    UUID snapshot = UUID.fromString("67000000-0000-0000-0002-%012d".formatted(sequence));
    UUID owner = UUID.fromString("67000000-0000-0000-0003-%012d".formatted(sequence));
    String parser =
        type == ForecastType.ULTRA_SHORT ? "kma-ultra-weather-v1" : "kma-village-weather-v1";
    String hash = "%064x".formatted(sequence);
    Instant forecastedAt =
        base.baseDate().atTime(base.baseTime()).atZone(ZoneId.of("Asia/Seoul")).toInstant();
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          finished_at, parser_version, schema_version, sync_mode, scope_key,
          request_fingerprint, idempotency_key, source_provider, source_service,
          owner_token, fencing_token
        ) values (?, 'weather_api', 'issue-67', ?, '2607', 'succeeded', ?, ?, ?, ?,
                  'snapshot', 'nx=60;ny=37', ?, ?, 'kma', 'VilageFcstInfoService_2.0', ?, 1)
        """,
        run,
        operation,
        Timestamp.from(fetchedAt.minusSeconds(1)),
        Timestamp.from(fetchedAt),
        parser,
        parser,
        hash,
        "issue-67-" + sequence,
        owner);
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'kma', 'VilageFcstInfoService_2.0', ?, 'nx=60;ny=37', ?,
                  'normalized', ?, ?, ?, '{}'::jsonb, '{"response":{}}'::jsonb, 15,
                  'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        operation,
        hash,
        Timestamp.from(fetchedAt),
        parser,
        "%064x".formatted(sequence + 100),
        Timestamp.from(fetchedAt));
    jdbc.update(
        """
        insert into public.weather_forecasts (
          grid_point_id, forecasted_at, valid_at, forecast_type, forecast_version,
          sky_code, precipitation_type, precipitation_probability_percent,
          precipitation_amount_mm, temperature_c, humidity_percent, wind_speed_mps,
          source_provider, source_operation, import_run_id, source_snapshot_id, raw_payload
        ) values (?, ?, ?, ?, ?, ?, ?, ?, 0.0, 27.5, 70, 2.1,
                  'kma', ?, ?, ?, '{}'::jsonb)
        """,
        GRID,
        Timestamp.from(forecastedAt),
        Timestamp.from(validAt),
        type == ForecastType.ULTRA_SHORT ? "ultra_short" : "short",
        type == ForecastType.ULTRA_SHORT ? null : "202608031400",
        type == ForecastType.ULTRA_SHORT ? null : "3",
        type == ForecastType.ULTRA_SHORT ? "0" : "1",
        type == ForecastType.ULTRA_SHORT ? null : 40,
        operation,
        run,
        snapshot);
  }
}
