package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherForecast;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherLineage;
import com.timingjeju.api.application.kma.KmaWeatherObservation;
import com.timingjeju.api.application.kma.KmaWeatherRepository;
import com.timingjeju.api.application.kma.KmaWeatherUpsertCommand;
import com.timingjeju.api.application.kma.KmaWeatherUpsertResult;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKmaWeatherRepository implements KmaWeatherRepository {

  private static final String PROVIDER = "kma";
  private final JdbcTemplate jdbc;

  public JdbcKmaWeatherRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc는 필수입니다.");
  }

  @Override
  public KmaWeatherUpsertResult upsert(KmaWeatherUpsertCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    try {
      requireGrid(command);
      Counts counts = new Counts();
      for (KmaWeatherObservation observation : command.batch().observations()) {
        upsertObservation(command.gridPointId(), observation, command.lineage(), counts);
      }
      for (KmaWeatherForecast forecast : command.batch().forecasts()) {
        upsertForecast(command.gridPointId(), forecast, command.lineage(), counts);
      }
      return new KmaWeatherUpsertResult(counts.inserted, counts.updated, counts.skipped);
    } catch (KmaWeatherImportException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw KmaWeatherImportException.storageFailure();
    }
  }

  private void requireGrid(KmaWeatherUpsertCommand command) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from public.weather_grid_points where id=? and grid_provider='KMA' and nx=? and ny=?",
            Integer.class,
            command.gridPointId(),
            command.batch().nx(),
            command.batch().ny());
    if (count == null || count != 1) throw KmaWeatherImportException.storageFailure();
  }

  private void upsertObservation(
      UUID gridPointId, KmaWeatherObservation value, KmaWeatherLineage lineage, Counts counts) {
    lock("observation", gridPointId, value.observedAt(), null, lineage.operationKey());
    StoredObservation stored =
        findObservation(gridPointId, value.observedAt(), lineage.operationKey());
    if (stored == null) {
      int changed =
          jdbc.update(
              """
              insert into public.weather_observations (
                grid_point_id, observed_at, base_date, base_time, temperature_c,
                precipitation_mm, precipitation_type, humidity_percent, wind_speed_mps,
                wind_direction_deg, source_provider, source_operation, import_run_id,
                source_snapshot_id, raw_payload
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
              """,
              gridPointId,
              ts(value.observedAt()),
              value.baseDate(),
              value.baseTime(),
              value.temperatureC(),
              value.precipitationMm(),
              value.precipitationType(),
              value.humidityPercent(),
              value.windSpeedMps(),
              value.windDirectionDeg(),
              PROVIDER,
              lineage.operationKey(),
              lineage.importRunId(),
              lineage.snapshotId());
      requireOne(changed);
      counts.inserted++;
      return;
    }
    if (stored.same(value, lineage)) {
      counts.skipped++;
      return;
    }
    int changed =
        jdbc.update(
            """
            update public.weather_observations
            set base_date=?, base_time=?, temperature_c=?, precipitation_mm=?,
                precipitation_type=?, humidity_percent=?, wind_speed_mps=?, wind_direction_deg=?,
                source_provider=?, source_operation=?, import_run_id=?, source_snapshot_id=?,
                raw_payload='{}'::jsonb
            where id=?
            """,
            value.baseDate(),
            value.baseTime(),
            value.temperatureC(),
            value.precipitationMm(),
            value.precipitationType(),
            value.humidityPercent(),
            value.windSpeedMps(),
            value.windDirectionDeg(),
            PROVIDER,
            lineage.operationKey(),
            lineage.importRunId(),
            lineage.snapshotId(),
            stored.id());
    requireOne(changed);
    counts.updated++;
  }

  private void upsertForecast(
      UUID gridPointId, KmaWeatherForecast value, KmaWeatherLineage lineage, Counts counts) {
    lock("forecast", gridPointId, value.forecastedAt(), value.validAt(), value.forecastType());
    StoredForecast stored =
        findForecast(gridPointId, value.forecastedAt(), value.validAt(), value.forecastType());
    if (stored == null) {
      int changed =
          jdbc.update(
              """
              insert into public.weather_forecasts (
                grid_point_id, forecasted_at, valid_at, forecast_type, forecast_version, sky_code,
                precipitation_type, precipitation_probability_percent, precipitation_amount_mm,
                temperature_c, min_temperature_c, max_temperature_c, humidity_percent,
                wind_speed_mps, precipitation_intensity_code, wind_strength_code,
                source_provider, source_operation, import_run_id,
                source_snapshot_id, raw_payload
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
              """,
              gridPointId,
              ts(value.forecastedAt()),
              ts(value.validAt()),
              value.forecastType(),
              value.forecastVersion(),
              value.skyCode(),
              value.precipitationType(),
              value.precipitationProbabilityPercent(),
              value.precipitationAmountMm(),
              value.temperatureC(),
              value.minTemperatureC(),
              value.maxTemperatureC(),
              value.humidityPercent(),
              value.windSpeedMps(),
              value.precipitationIntensityCode(),
              value.windStrengthCode(),
              PROVIDER,
              lineage.operationKey(),
              lineage.importRunId(),
              lineage.snapshotId());
      requireOne(changed);
      counts.inserted++;
      return;
    }
    if (stored.same(value, lineage)) {
      counts.skipped++;
      return;
    }
    int changed =
        jdbc.update(
            """
            update public.weather_forecasts
            set forecast_version=?, sky_code=?, precipitation_type=?,
                precipitation_probability_percent=?, precipitation_amount_mm=?, temperature_c=?,
                min_temperature_c=?, max_temperature_c=?, humidity_percent=?, wind_speed_mps=?,
                precipitation_intensity_code=?, wind_strength_code=?,
                source_provider=?, source_operation=?,
                import_run_id=?, source_snapshot_id=?, raw_payload='{}'::jsonb
            where id=?
            """,
            value.forecastVersion(),
            value.skyCode(),
            value.precipitationType(),
            value.precipitationProbabilityPercent(),
            value.precipitationAmountMm(),
            value.temperatureC(),
            value.minTemperatureC(),
            value.maxTemperatureC(),
            value.humidityPercent(),
            value.windSpeedMps(),
            value.precipitationIntensityCode(),
            value.windStrengthCode(),
            PROVIDER,
            lineage.operationKey(),
            lineage.importRunId(),
            lineage.snapshotId(),
            stored.id());
    requireOne(changed);
    counts.updated++;
  }

  private StoredObservation findObservation(
      UUID gridPointId, Instant observedAt, String operation) {
    List<StoredObservation> rows =
        jdbc.query(
            """
            select id, base_date, base_time, temperature_c, precipitation_mm,
                   precipitation_type, humidity_percent, wind_speed_mps, wind_direction_deg,
                   import_run_id, source_snapshot_id
            from public.weather_observations
            where grid_point_id=? and observed_at=? and source_operation=?
            for update
            """,
            (rs, row) -> observation(rs),
            gridPointId,
            ts(observedAt),
            operation);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private StoredForecast findForecast(
      UUID gridPointId, Instant forecastedAt, Instant validAt, String forecastType) {
    List<StoredForecast> rows =
        jdbc.query(
            """
            select id, forecast_version, sky_code, precipitation_type,
                   precipitation_probability_percent, precipitation_amount_mm, temperature_c,
                   min_temperature_c, max_temperature_c, humidity_percent, wind_speed_mps,
                   precipitation_intensity_code, wind_strength_code,
                   source_operation, import_run_id,
                   source_snapshot_id
            from public.weather_forecasts
            where grid_point_id=? and forecasted_at=? and valid_at=? and forecast_type=?
            for update
            """,
            (rs, row) -> forecast(rs),
            gridPointId,
            ts(forecastedAt),
            ts(validAt),
            forecastType);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void lock(String kind, UUID grid, Instant first, Instant second, String discriminator) {
    String key =
        PROVIDER
            + '\u001f'
            + kind
            + '\u001f'
            + grid
            + '\u001f'
            + first
            + '\u001f'
            + Objects.toString(second, "")
            + '\u001f'
            + discriminator;
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, key);
  }

  private static StoredObservation observation(ResultSet rs) throws SQLException {
    return new StoredObservation(
        rs.getObject("id", UUID.class),
        rs.getDate("base_date").toLocalDate(),
        rs.getTime("base_time").toLocalTime(),
        rs.getBigDecimal("temperature_c"),
        rs.getBigDecimal("precipitation_mm"),
        rs.getString("precipitation_type"),
        rs.getInt("humidity_percent"),
        rs.getBigDecimal("wind_speed_mps"),
        rs.getInt("wind_direction_deg"),
        rs.getObject("import_run_id", UUID.class),
        rs.getObject("source_snapshot_id", UUID.class));
  }

  private static StoredForecast forecast(ResultSet rs) throws SQLException {
    return new StoredForecast(
        rs.getObject("id", UUID.class),
        rs.getString("forecast_version"),
        rs.getString("sky_code"),
        rs.getString("precipitation_type"),
        rs.getObject("precipitation_probability_percent", Integer.class),
        rs.getBigDecimal("precipitation_amount_mm"),
        rs.getBigDecimal("temperature_c"),
        rs.getBigDecimal("min_temperature_c"),
        rs.getBigDecimal("max_temperature_c"),
        rs.getInt("humidity_percent"),
        rs.getBigDecimal("wind_speed_mps"),
        rs.getObject("precipitation_intensity_code", Integer.class),
        rs.getObject("wind_strength_code", Integer.class),
        rs.getString("source_operation"),
        rs.getObject("import_run_id", UUID.class),
        rs.getObject("source_snapshot_id", UUID.class));
  }

  private static void requireOne(int changed) {
    if (changed != 1) throw KmaWeatherImportException.storageFailure();
  }

  private static Timestamp ts(Instant value) {
    return Timestamp.from(value);
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  private static boolean sameNullableDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static final class Counts {
    private int inserted;
    private int updated;
    private int skipped;
  }

  private record StoredObservation(
      UUID id,
      java.time.LocalDate baseDate,
      java.time.LocalTime baseTime,
      BigDecimal temperature,
      BigDecimal precipitation,
      String precipitationType,
      int humidity,
      BigDecimal windSpeed,
      int windDirection,
      UUID runId,
      UUID snapshotId) {
    private boolean same(KmaWeatherObservation value, KmaWeatherLineage lineage) {
      return baseDate.equals(value.baseDate())
          && baseTime.equals(value.baseTime())
          && sameDecimal(temperature, value.temperatureC())
          && sameDecimal(precipitation, value.precipitationMm())
          && Objects.equals(precipitationType, value.precipitationType())
          && humidity == value.humidityPercent()
          && sameDecimal(windSpeed, value.windSpeedMps())
          && windDirection == value.windDirectionDeg()
          && runId.equals(lineage.importRunId())
          && snapshotId.equals(lineage.snapshotId());
    }
  }

  private record StoredForecast(
      UUID id,
      String forecastVersion,
      String sky,
      String precipitationType,
      Integer precipitationProbability,
      BigDecimal precipitation,
      BigDecimal temperature,
      BigDecimal minimumTemperature,
      BigDecimal maximumTemperature,
      int humidity,
      BigDecimal windSpeed,
      Integer precipitationIntensity,
      Integer windStrength,
      String operation,
      UUID runId,
      UUID snapshotId) {
    private boolean same(KmaWeatherForecast value, KmaWeatherLineage lineage) {
      return Objects.equals(forecastVersion, value.forecastVersion())
          && Objects.equals(sky, value.skyCode())
          && Objects.equals(precipitationType, value.precipitationType())
          && Objects.equals(precipitationProbability, value.precipitationProbabilityPercent())
          && sameNullableDecimal(precipitation, value.precipitationAmountMm())
          && sameDecimal(temperature, value.temperatureC())
          && sameNullableDecimal(minimumTemperature, value.minTemperatureC())
          && sameNullableDecimal(maximumTemperature, value.maxTemperatureC())
          && humidity == value.humidityPercent()
          && sameNullableDecimal(windSpeed, value.windSpeedMps())
          && Objects.equals(precipitationIntensity, value.precipitationIntensityCode())
          && Objects.equals(windStrength, value.windStrengthCode())
          && Objects.equals(operation, lineage.operationKey())
          && runId.equals(lineage.importRunId())
          && snapshotId.equals(lineage.snapshotId());
    }
  }
}
