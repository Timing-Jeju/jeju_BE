package com.timingjeju.api.global.weather;

import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.domain.weather.ForecastType;
import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.exception.WeatherForecastDataUnavailableException;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import com.timingjeju.api.domain.weather.model.WeatherForecastLookup;
import com.timingjeju.api.domain.weather.model.WeatherForecastSnapshot;
import com.timingjeju.api.domain.weather.repository.WeatherForecastRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWeatherForecastRepository implements WeatherForecastRepository {

  static final String SELECT_GRID =
      """
      select nx, ny, region_name
      from public.weather_grid_points
      where grid_provider='KMA' and nx=:nx and ny=:ny
      limit 1
      """;

  static final String SELECT_FORECAST =
      """
      select f.forecasted_at, f.valid_at, f.temperature_c,
             f.precipitation_probability_percent, f.precipitation_amount_mm,
             f.precipitation_type, f.sky_code, f.humidity_percent, f.wind_speed_mps,
             snapshot.fetched_at
      from public.weather_grid_points grid
      join public.weather_forecasts f on f.grid_point_id=grid.id
      join public.external_api_snapshots snapshot on snapshot.id=f.source_snapshot_id
      join public.data_import_runs import_run on import_run.id=f.import_run_id
      where grid.grid_provider='KMA' and grid.nx=:nx and grid.ny=:ny
        and f.forecast_type=:forecastType
        and f.forecasted_at=:forecastedAt
        and f.valid_at=:validAt
        and lower(f.source_provider)='kma'
        and f.source_operation=:sourceOperation
        and lower(snapshot.source_provider)='kma'
        and snapshot.source_service='VilageFcstInfoService_2.0'
        and snapshot.source_operation=:sourceOperation
        and snapshot.parse_status='parsed'
        and snapshot.import_run_id=f.import_run_id
        and import_run.status='succeeded'
        and (:forecastType <> 'short'
             or (f.forecast_version is not null and f.forecast_version ~ '^[0-9]{12}$'))
        and (
          (:forecastType='ultra_short'
           and f.temperature_c is not null
           and f.precipitation_amount_mm is not null
           and f.precipitation_type is not null
           and f.humidity_percent is not null
           and f.wind_speed_mps is not null
           and f.precipitation_probability_percent is null
           and f.sky_code is null
           and f.precipitation_intensity_code is null
           and f.wind_strength_code is null)
          or
          (:forecastType='short'
           and f.temperature_c is not null
           and f.precipitation_probability_percent is not null
           and f.precipitation_type is not null
           and f.sky_code is not null
           and f.humidity_percent is not null
           and ((f.precipitation_amount_mm is not null
                 and f.precipitation_intensity_code is null)
                or (f.precipitation_amount_mm is null
                    and f.precipitation_intensity_code is not null))
           and ((f.wind_speed_mps is not null and f.wind_strength_code is null)
                or (f.wind_speed_mps is null and f.wind_strength_code is not null)))
        )
      limit 2
      """;

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private static final List<LocalTime> VILLAGE_BASES =
      List.of(
          LocalTime.of(2, 0),
          LocalTime.of(5, 0),
          LocalTime.of(8, 0),
          LocalTime.of(11, 0),
          LocalTime.of(14, 0),
          LocalTime.of(17, 0),
          LocalTime.of(20, 0),
          LocalTime.of(23, 0));

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcWeatherForecastRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SupportedWeatherGrid> findSupportedGrid(KmaGridPoint gridPoint) {
    try {
      List<SupportedWeatherGrid> rows =
          jdbc.query(
              SELECT_GRID,
              new MapSqlParameterSource()
                  .addValue("nx", gridPoint.nx())
                  .addValue("ny", gridPoint.ny()),
              (resultSet, rowNumber) ->
                  new SupportedWeatherGrid(
                      new KmaGridPoint(resultSet.getInt("nx"), resultSet.getInt("ny")),
                      resultSet.getString("region_name")));
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw new WeatherForecastDataUnavailableException();
    }
  }

  @Override
  public Optional<WeatherForecastSnapshot> find(WeatherForecastLookup lookup) {
    String storageType =
        lookup.forecastType() == ForecastType.ULTRA_SHORT ? "ultra_short" : "short";
    String operation =
        lookup.forecastType() == ForecastType.ULTRA_SHORT ? "getUltraSrtFcst" : "getVilageFcst";
    Instant baseInstant =
        LocalDateTime.of(lookup.base().baseDate(), lookup.base().baseTime())
            .atZone(KOREA)
            .toInstant();
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("nx", lookup.gridPoint().nx())
            .addValue("ny", lookup.gridPoint().ny())
            .addValue("forecastType", storageType)
            .addValue(
                "forecastedAt",
                OffsetDateTime.ofInstant(baseInstant, ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue(
                "validAt",
                OffsetDateTime.ofInstant(lookup.validAt(), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("sourceOperation", operation);
    try {
      List<WeatherForecastSnapshot> rows =
          jdbc.query(
              SELECT_FORECAST,
              parameters,
              (resultSet, rowNumber) -> map(resultSet, lookup.forecastType(), lookup.base()));
      if (rows.size() > 1) {
        throw new WeatherForecastDataUnavailableException();
      }
      return rows.stream().findFirst();
    } catch (DataAccessException failure) {
      throw new WeatherForecastDataUnavailableException();
    }
  }

  private static WeatherForecastSnapshot map(
      ResultSet resultSet, ForecastType type, ForecastBaseTime base) throws SQLException {
    Instant observedAt = resultSet.getTimestamp("fetched_at").toInstant();
    return new WeatherForecastSnapshot(
        type,
        base,
        resultSet.getTimestamp("forecasted_at").toInstant(),
        resultSet.getTimestamp("valid_at").toInstant(),
        resultSet.getBigDecimal("temperature_c"),
        resultSet.getObject("precipitation_probability_percent", Integer.class),
        resultSet.getBigDecimal("precipitation_amount_mm"),
        resultSet.getString("precipitation_type"),
        resultSet.getString("sky_code"),
        resultSet.getObject("humidity_percent", Integer.class),
        resultSet.getBigDecimal("wind_speed_mps"),
        observedAt,
        expiresAt(type, base, observedAt));
  }

  private static Instant expiresAt(ForecastType type, ForecastBaseTime base, Instant observedAt) {
    if (type == ForecastType.ULTRA_SHORT) {
      return observedAt.plus(Duration.ofMinutes(10));
    }
    int index = VILLAGE_BASES.indexOf(base.baseTime());
    if (index < 0) {
      throw new WeatherForecastDataUnavailableException();
    }
    LocalDateTime nextBase =
        index == VILLAGE_BASES.size() - 1
            ? LocalDateTime.of(base.baseDate().plusDays(1), VILLAGE_BASES.getFirst())
            : LocalDateTime.of(base.baseDate(), VILLAGE_BASES.get(index + 1));
    return nextBase.plusMinutes(10).atZone(KOREA).toInstant();
  }
}
