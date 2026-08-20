package com.timingjeju.api.global.tago.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopPageLineage;
import com.timingjeju.api.application.tago.stop.TagoStopRepository;
import com.timingjeju.api.application.tago.stop.TagoStopWrite;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcTagoStopRepositoryIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID RUN = UUID.fromString("35000000-0000-0000-0000-000000000011");
  private static final UUID CITY = UUID.fromString("35000000-0000-0000-0000-000000000012");
  private static final UUID STATION = UUID.fromString("35000000-0000-0000-0000-000000000013");
  @Autowired private TagoStopRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshots(RUN, CITY, STATION, NOW);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void natural_key_UUID는_재수집에도_안정적이고_다른_city의_같은_node는_충돌하지_않는다() {
    repository.apply(
        city("39"),
        cityLineage(CITY),
        stationLineage(STATION),
        List.of(write("39", RUN, STATION, NOW)),
        RUN,
        NOW);
    UUID firstId = stopId("39");
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW),
        RUN);

    UUID nextRun = UUID.fromString("35000000-0000-0000-0000-000000000021");
    UUID nextCity = UUID.fromString("35000000-0000-0000-0000-000000000022");
    UUID nextStation = UUID.fromString("35000000-0000-0000-0000-000000000023");
    insertRunAndSnapshots(nextRun, nextCity, nextStation, NOW.plusSeconds(1));
    var replay =
        repository.apply(
            city("39"),
            cityLineage(nextCity),
            stationLineage(nextStation),
            List.of(write("39", nextRun, nextStation, NOW.plusSeconds(1))),
            nextRun,
            NOW.plusSeconds(1));
    repository.apply(
        city("40"),
        cityLineage(nextCity),
        stationLineage(nextStation),
        List.of(write("40", nextRun, nextStation, NOW.plusSeconds(1))),
        nextRun,
        NOW.plusSeconds(1));

    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(stopId("39")).isEqualTo(firstId);
    assertThat(jdbcTemplate.queryForObject("select count(*) from public.bus_stops", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void 오래된_snapshot은_새값을_덮지_않고_같은_batch의_lineage_mismatch는_전체_rollback한다() {
    repository.apply(
        city("39"),
        cityLineage(CITY),
        stationLineage(STATION),
        List.of(write("39", RUN, STATION, NOW)),
        RUN,
        NOW);
    var stale =
        repository.apply(
            city("39"),
            cityLineage(CITY),
            stationLineage(STATION),
            List.of(write("39", RUN, STATION, NOW.minusSeconds(1))),
            RUN,
            NOW);
    assertThat(stale.skipped()).isEqualTo(1);

    UUID invalidRun = UUID.fromString("35000000-0000-0000-0000-000000000099");
    assertThatThrownBy(
            () ->
                repository.apply(
                    city("41"),
                    cityLineage(CITY),
                    stationLineage(STATION),
                    List.of(write("41", invalidRun, STATION, NOW)),
                    RUN,
                    NOW))
        .isInstanceOf(TagoStopImportException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.external_reference_codes where external_code='41'",
                Integer.class))
        .isZero();
  }

  @Test
  void 누락된_기존_stop은_full_sync에서_stale되고_RLS와_scope_index가_유지된다() {
    repository.apply(
        city("39"),
        cityLineage(CITY),
        stationLineage(STATION),
        List.of(write("39", RUN, STATION, NOW)),
        RUN,
        NOW);
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW),
        RUN);
    UUID nextRun = UUID.fromString("35000000-0000-0000-0000-000000000031");
    UUID nextCity = UUID.fromString("35000000-0000-0000-0000-000000000032");
    UUID nextStation = UUID.fromString("35000000-0000-0000-0000-000000000033");
    insertRunAndSnapshots(nextRun, nextCity, nextStation, NOW.plusSeconds(1));
    repository.apply(
        city("39"),
        cityLineage(nextCity),
        stationLineage(nextStation),
        List.of(),
        nextRun,
        NOW.plusSeconds(1));

    assertThat(
            jdbcTemplate.queryForObject(
                "select stale from public.bus_stops where city_code='39'", Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.bus_stops'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname='public' and indexname='idx_bus_stops_source_scope_freshness'",
                Integer.class))
        .isEqualTo(1);
  }

  @RepeatedTest(3)
  void 같은_natural_key를_동시에_적재해도_행은_하나이고_한쪽은_replay된다() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var calls =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  ignored ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            start.await(10, TimeUnit.SECONDS);
                            return repository.apply(
                                city("39"),
                                cityLineage(CITY),
                                stationLineage(STATION),
                                List.of(write("39", RUN, STATION, NOW)),
                                RUN,
                                NOW);
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var results =
          List.of(calls.get(0).get(30, TimeUnit.SECONDS), calls.get(1).get(30, TimeUnit.SECONDS));
      assertThat(results).extracting(result -> result.inserted()).containsExactlyInAnyOrder(1, 0);
      assertThat(results).extracting(result -> result.skipped()).containsExactlyInAnyOrder(0, 1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(jdbcTemplate.queryForObject("select count(*) from public.bus_stops", Integer.class))
        .isEqualTo(1);
  }

  private TagoCityCode city(String code) {
    return new TagoCityCode(code, code.equals("39") ? "제주특별자치도" : "다른 도시");
  }

  private TagoStopPageLineage cityLineage(UUID snapshot) {
    return new TagoStopPageLineage("city", 0, 1, snapshot, "a".repeat(64), NOW);
  }

  private TagoStopPageLineage stationLineage(UUID snapshot) {
    return new TagoStopPageLineage("station", 1, 1, snapshot, "b".repeat(64), NOW);
  }

  private TagoStopWrite write(String cityCode, UUID run, UUID snapshot, Instant observedAt) {
    return new TagoStopWrite(
        new TagoStation(cityCode, "NODE-1", "101", "정류장", 126.5, 33.5), snapshot, run, observedAt);
  }

  private UUID stopId(String cityCode) {
    return jdbcTemplate.queryForObject(
        "select id from public.bus_stops where source_provider='TAGO' and source_service='BusSttnInfoInqireService' and city_code=? and node_id='NODE-1'",
        UUID.class,
        cityCode);
  }

  private void insertRunAndSnapshots(
      UUID run, UUID citySnapshot, UUID stationSnapshot, Instant fetchedAt) {
    String requestHash = run.toString().replace("-", "") + "0".repeat(32);
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tago', 'fixture', 'getSttnNoList', '2026', 'running', ?, 'tago-stop-v1',
                  'tago-stop-v1', 'full', 'jeju', ?, ?, 'TAGO', 'BusSttnInfoInqireService')
        """,
        run,
        Timestamp.from(fetchedAt),
        requestHash,
        "issue-35-" + run);
    insertSnapshot(citySnapshot, run, "city-1", fetchedAt, "b".repeat(64));
    insertSnapshot(stationSnapshot, run, "station-1", fetchedAt, "c".repeat(64));
  }

  private void insertSnapshot(
      UUID snapshot, UUID run, String pageKey, Instant fetchedAt, String payloadHash) {
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'TAGO', 'BusSttnInfoInqireService', 'getSttnNoList', 'jeju', ?, ?, ?,
                  'tago-stop-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1', 'JSON',
                  'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        payloadHash,
        pageKey,
        Timestamp.from(fetchedAt),
        payloadHash,
        Timestamp.from(fetchedAt));
  }

  private void clean() {
    jdbcTemplate.update(
        "delete from public.bus_stops where source_provider='TAGO' and source_service='BusSttnInfoInqireService'");
    jdbcTemplate.update(
        "delete from public.external_reference_codes where source_provider='TAGO' and source_service='BusSttnInfoInqireService'");
    jdbcTemplate.update(
        "delete from public.external_api_snapshots where source_provider='TAGO' and source_service='BusSttnInfoInqireService'");
    jdbcTemplate.update(
        "delete from public.data_import_runs where source_provider='TAGO' and source_service='BusSttnInfoInqireService' and source_name='fixture'");
  }
}
