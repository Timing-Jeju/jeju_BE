package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportCheckpointError;
import com.timingjeju.api.application.importing.ImportCheckpointException;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.kma.KmaWeatherBatch;
import com.timingjeju.api.application.kma.KmaWeatherCommitCommand;
import com.timingjeju.api.application.kma.KmaWeatherCommitter;
import com.timingjeju.api.application.kma.KmaWeatherForecast;
import com.timingjeju.api.application.kma.KmaWeatherLineage;
import com.timingjeju.api.application.kma.KmaWeatherObservation;
import com.timingjeju.api.application.kma.KmaWeatherRepository;
import com.timingjeju.api.application.kma.KmaWeatherUpsertCommand;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest(properties = "timing-jeju.test.context=kma-weather-importer")
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcKmaWeatherRepositoryIntegrationTest {

  private static final UUID GRID = UUID.fromString("43000000-0000-0000-0000-000000000100");
  private static final Instant FORECASTED = Instant.parse("2026-08-15T15:30:00Z");
  private static final Instant VALID = Instant.parse("2026-08-15T16:00:00Z");
  private static final Instant OBSERVED = Instant.parse("2026-08-15T15:00:00Z");
  private static final ImportRunScope SCOPE =
      new ImportRunScope("kma", "VilageFcstInfoService_2.0", "getUltraSrtFcst", "nx=52;ny=38");

  @Autowired private KmaWeatherRepository repository;
  @Autowired private KmaWeatherCommitter committer;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void setUp() {
    clean();
    jdbc.update(
        "insert into public.weather_grid_points(id,grid_provider,nx,ny,region_name) values (?,'KMA',52,38,'제주공항')",
        GRID);
    jdbc.update(
        """
        insert into public.data_import_checkpoints(
          source_provider,source_service,source_operation,scope_key,checkpoint,source_watermark_at)
        values ('kma','VilageFcstInfoService_2.0','getUltraSrtFcst','nx=52;ny=38',
                '{}'::jsonb,'1970-01-01T00:00:00Z')
        """);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void identicalSnapshotRunReplaySkipsAndPreservesSingleLineageRow() {
    Fixture fixture = fixture(1);
    KmaWeatherUpsertCommand command = upsert(fixture, "25.0");
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    var first = tx.execute(status -> repository.upsert(command));
    var replay = tx.execute(status -> repository.upsert(command));

    assertThat(first.inserted()).isEqualTo(1);
    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from public.weather_forecasts", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot_id from public.weather_forecasts", UUID.class))
        .isEqualTo(fixture.snapshot());
    assertThat(
            jdbc.queryForObject(
                "select raw_payload = '{}'::jsonb from public.weather_forecasts", Boolean.class))
        .isTrue();
  }

  @Test
  void newSnapshotAndMatchingRunCanUpdateSameNaturalKey() {
    Fixture first = fixture(1);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> repository.upsert(upsert(first, "25.0")));
    finish(first);
    Fixture second = fixture(2);

    var result = tx.execute(status -> repository.upsert(upsert(second, "26.5")));

    assertThat(result.updated()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select temperature_c from public.weather_forecasts", BigDecimal.class))
        .isEqualByComparingTo("26.5");
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot_id from public.weather_forecasts", UUID.class))
        .isEqualTo(second.snapshot());
    assertThat(
            jdbc.queryForObject("select import_run_id from public.weather_forecasts", UUID.class))
        .isEqualTo(second.run());
  }

  @Test
  void villageForecastPersistsVersionAndEveryNormalizedCategoryWithSingleLineage() {
    Fixture fixture = fixture(21, "getVilageFcst");
    KmaWeatherForecast forecast =
        new KmaWeatherForecast(
            Instant.parse("2026-08-15T20:00:00Z"),
            Instant.parse("2026-08-15T21:00:00Z"),
            "short",
            "202608160500",
            "3",
            "1",
            30,
            new BigDecimal("0.5"),
            new BigDecimal("23.0"),
            new BigDecimal("19.0"),
            new BigDecimal("28.0"),
            80,
            new BigDecimal("2.4"));
    KmaWeatherBatch batch =
        new KmaWeatherBatch(52, 38, 9, forecast.validAt(), List.of(), List.of(forecast));

    repository.upsert(new KmaWeatherUpsertCommand(GRID, batch, fixture.lineage()));

    assertThat(
            jdbc.queryForMap(
                "select forecast_version, precipitation_probability_percent, min_temperature_c, max_temperature_c, source_snapshot_id, import_run_id from public.weather_forecasts"))
        .containsEntry("forecast_version", "202608160500")
        .containsEntry("precipitation_probability_percent", 30)
        .containsEntry("min_temperature_c", new BigDecimal("19.00"))
        .containsEntry("max_temperature_c", new BigDecimal("28.00"))
        .containsEntry("source_snapshot_id", fixture.snapshot())
        .containsEntry("import_run_id", fixture.run());
  }

  @Test
  void observationReplaySkipsAndNewLineageUpdatesSameNaturalKey() {
    Fixture first = fixture(11, "getUltraSrtNcst");
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    var inserted = tx.execute(status -> repository.upsert(observation(first, "25.0")));
    var replay = tx.execute(status -> repository.upsert(observation(first, "25.0")));
    finish(first);
    Fixture second = fixture(12, "getUltraSrtNcst");
    var updated = tx.execute(status -> repository.upsert(observation(second, "26.5")));

    assertThat(inserted.inserted()).isEqualTo(1);
    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(updated.updated()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject("select count(*) from public.weather_observations", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select temperature_c from public.weather_observations", BigDecimal.class))
        .isEqualByComparingTo("26.5");
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot_id from public.weather_observations", UUID.class))
        .isEqualTo(second.snapshot());
    assertThat(
            jdbc.queryForObject(
                "select raw_payload = '{}'::jsonb from public.weather_observations", Boolean.class))
        .isTrue();
  }

  @Test
  void concurrentSameNaturalKeyCreatesExactlyOneRow() throws Exception {
    Fixture fixture = fixture(1);
    KmaWeatherUpsertCommand command = upsert(fixture, "25.0");

    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(status -> repository.upsert(command)));
      var second =
          pool.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(status -> repository.upsert(command)));

      assertThat(List.of(first.get(), second.get()))
          .extracting(result -> result.inserted() + result.skipped())
          .containsExactlyInAnyOrder(1, 1);
    }
    assertThat(jdbc.queryForObject("select count(*) from public.weather_forecasts", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void staleCheckpointCasRollsBackWeatherAndRunSuccessTogether() {
    Fixture accepted = fixture(1);
    committer.commit(commit(accepted, 0, "25.0"));
    Fixture stale = fixture(2);

    assertThatThrownBy(() -> committer.commit(commit(stale, 0, "99.0")))
        .isInstanceOf(ImportCheckpointException.class)
        .satisfies(
            failure ->
                assertThat(((ImportCheckpointException) failure).code())
                    .isEqualTo(ImportCheckpointError.STALE_VERSION));

    assertThat(
            jdbc.queryForObject(
                "select temperature_c from public.weather_forecasts", BigDecimal.class))
        .isEqualByComparingTo("25.0");
    assertThat(
            jdbc.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, stale.run()))
        .isEqualTo("running");
    assertThat(
            jdbc.queryForObject(
                "select last_succeeded_run_id from public.data_import_checkpoints where source_operation='getUltraSrtFcst'",
                UUID.class))
        .isEqualTo(accepted.run());
  }

  private KmaWeatherCommitCommand commit(Fixture fixture, long version, String temperature) {
    KmaWeatherUpsertCommand write = upsert(fixture, temperature);
    return new KmaWeatherCommitCommand(
        fixture.lease(),
        SCOPE,
        version,
        GRID,
        new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(0, 30)),
        write.batch(),
        write.lineage(),
        fixture.fetchedAt(),
        false);
  }

  private KmaWeatherUpsertCommand upsert(Fixture fixture, String temperature) {
    KmaWeatherForecast forecast =
        new KmaWeatherForecast(
            FORECASTED,
            VALID,
            "ultra_short",
            "1",
            "0",
            BigDecimal.ZERO,
            new BigDecimal(temperature),
            70,
            new BigDecimal("2.0"));
    KmaWeatherBatch batch = new KmaWeatherBatch(52, 38, 6, VALID, List.of(), List.of(forecast));
    return new KmaWeatherUpsertCommand(GRID, batch, fixture.lineage());
  }

  private KmaWeatherUpsertCommand observation(Fixture fixture, String temperature) {
    KmaWeatherObservation observation =
        new KmaWeatherObservation(
            OBSERVED,
            LocalDate.of(2026, 8, 16),
            LocalTime.MIDNIGHT,
            new BigDecimal(temperature),
            BigDecimal.ZERO,
            "0",
            70,
            new BigDecimal("2.0"),
            360);
    KmaWeatherBatch batch =
        new KmaWeatherBatch(52, 38, 6, OBSERVED, List.of(observation), List.of());
    return new KmaWeatherUpsertCommand(GRID, batch, fixture.lineage());
  }

  private Fixture fixture(int sequence) {
    return fixture(sequence, "getUltraSrtFcst");
  }

  private Fixture fixture(int sequence, String operation) {
    UUID run = UUID.fromString("43000000-0000-0000-0001-%012d".formatted(sequence));
    UUID snapshot = UUID.fromString("43000000-0000-0000-0002-%012d".formatted(sequence));
    UUID owner = UUID.fromString("43000000-0000-0000-0003-%012d".formatted(sequence));
    String hash = "%064x".formatted(sequence);
    Instant fetchedAt = FORECASTED.plusSeconds(sequence);
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service, owner_token, fencing_token
        ) values (?, 'weather_api', 'issue-43', ?, '2607', 'running', ?,
                  'kma-ultra-weather-v1', 'kma-ultra-weather-v1', 'snapshot', 'nx=52;ny=38', ?, ?,
                  'kma', 'VilageFcstInfoService_2.0', ?, 1)
        """,
        run,
        operation,
        Timestamp.from(fetchedAt),
        hash,
        "issue-43-" + sequence,
        owner);
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'kma', 'VilageFcstInfoService_2.0', ?, 'nx=52;ny=38',
                  ?, '202608160030', ?, 'kma-ultra-weather-v1', ?, '{}'::jsonb,
                  '{"response":{}}'::jsonb, 15, 'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        operation,
        hash,
        Timestamp.from(fetchedAt),
        "%064x".formatted(sequence + 100),
        Timestamp.from(fetchedAt));
    return new Fixture(run, snapshot, owner, hash, fetchedAt, operation);
  }

  private void finish(Fixture fixture) {
    jdbc.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(fixture.fetchedAt().plusSeconds(1)),
        fixture.run());
  }

  private void clean() {
    jdbc.update("delete from public.weather_forecasts");
    jdbc.update("delete from public.weather_observations");
    jdbc.update("delete from public.external_api_snapshots where source_provider='kma'");
    jdbc.execute(
        "alter table public.data_import_checkpoints disable trigger trg_data_import_checkpoints_no_delete");
    jdbc.update("delete from public.data_import_checkpoints where source_provider='kma'");
    jdbc.execute(
        "alter table public.data_import_checkpoints enable trigger trg_data_import_checkpoints_no_delete");
    jdbc.update("delete from public.data_import_runs where source_provider='kma'");
    jdbc.update("delete from public.weather_grid_points where id=?", GRID);
  }

  private record Fixture(
      UUID run,
      UUID snapshot,
      UUID owner,
      String requestHash,
      Instant fetchedAt,
      String operation) {
    private ImportRunLease lease() {
      return new ImportRunLease(run, owner, 1);
    }

    private KmaWeatherLineage lineage() {
      return new KmaWeatherLineage(operation, requestHash, snapshot, run);
    }
  }
}
