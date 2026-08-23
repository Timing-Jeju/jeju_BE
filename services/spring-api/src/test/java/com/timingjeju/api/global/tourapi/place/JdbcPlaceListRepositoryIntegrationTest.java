package com.timingjeju.api.global.tourapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunScope;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryCommitCommand;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryCommitter;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportException;
import com.timingjeju.api.application.tourapi.place.PlaceAliasWrite;
import com.timingjeju.api.application.tourapi.place.PlaceLineage;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListRepository;
import com.timingjeju.api.application.tourapi.place.PlaceListUpsertCommand;
import com.timingjeju.api.application.tourapi.place.PlaceListWrite;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class JdbcPlaceListRepositoryIntegrationTest {

  private static final UUID RUN = UUID.fromString("26000000-0000-0000-0000-000000000001");
  private static final UUID SNAPSHOT = UUID.fromString("26000000-0000-0000-0000-000000000002");
  private static final UUID SEED_PLACE_ID = UUID.fromString("26000000-0000-0000-0000-000000000102");
  private static final UUID LEGACY_RUN = UUID.fromString("26000000-0000-0000-0000-000000000111");
  private static final UUID LEGACY_SNAPSHOT =
      UUID.fromString("26000000-0000-0000-0000-000000000112");
  private static final String HASH =
      "2626262626262626262626262626262626262626262626262626262626262626";
  private static final String LEGACY_HASH =
      "3636363636363636363636363636363636363636363636363636363636363636";
  private static final Instant NOW = Instant.parse("2026-08-16T03:00:00Z");

  @Autowired private PlaceListRepository repository;
  @Autowired private DiscoveryCommitter discoveryCommitter;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshot(RUN, SNAPSHOT, HASH);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void contentid_natural_key로_place와_source를_한번_저장하고_공통_provenance를_각각_남긴다() {
    var command = command(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW);

    var first = repository.upsert(command);
    UUID placeId = jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class);
    UUID sourceId =
        jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class);
    var replay = repository.upsert(command);

    assertThat(first.inserted()).isEqualTo(1);
    assertThat(replay.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(placeId);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class))
        .isEqualTo(sourceId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "select normalized_entity_type from public.tour_api_operation_provenance",
                String.class))
        .containsExactlyInAnyOrder("tour_place_sources", "tour_places");
    assertThat(
            jdbcTemplate.queryForObject(
                "select l_dong_regn_cd from public.tour_place_sources", String.class))
        .isEqualTo("50");
    assertThat(
            jdbcTemplate.queryForObject(
                "select lcls_systm3 from public.tour_place_sources", String.class))
        .isEqualTo("VE0101");
  }

  @Test
  void 실제_writer는_lclsSystm1을_trim_uppercase하고_없으면_contentTypeId로_fallback한다() {
    repository.upsert(
        new PlaceListUpsertCommand(
            List.of(
                write(
                    placeWithCategory("100", "writer code", "12", " ve "),
                    RUN,
                    SNAPSHOT,
                    HASH,
                    NOW),
                write(
                    placeWithCategory("101", "writer fallback", "99", null),
                    RUN,
                    SNAPSHOT,
                    HASH,
                    NOW))));

    assertThat(
            jdbcTemplate.queryForList(
                "select category from public.tour_places order by content_id", String.class))
        .containsExactly("VE", "content-type:99");
  }

  @Test
  void 한_batch의_duplicate_contentid도_장소행을_중복생성하지_않는다() {
    PlaceListWrite write = write(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW);

    var result = repository.upsert(new PlaceListUpsertCommand(List.of(write, write)));

    assertThat(result.inserted()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 기존_tour_places_자연키가_있으면_동일_contentid_시_source없이_재사용하고_업데이트한다() {
    insertLegacyPlaceWithoutSource(SEED_PLACE_ID, "126435", "한국관광공사", "TourAPI 국문 관광정보", "구성산일출봉");

    var result =
        repository.upsert(
            command(place("126435", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW.plusSeconds(1)));

    assertThat(result.inserted()).isEqualTo(0);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(0);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(SEED_PLACE_ID);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_provider from public.tour_place_sources where place_id=?",
                String.class,
                SEED_PLACE_ID))
        .isEqualTo("tour-api");
    assertThat(
            jdbcTemplate.queryForObject(
                "select name from public.tour_places where id=?", String.class, SEED_PLACE_ID))
        .isEqualTo("성산일출봉");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_provider from public.tour_places where id=?",
                String.class,
                SEED_PLACE_ID))
        .isEqualTo("tour-api");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_service from public.tour_places where id=?",
                String.class,
                SEED_PLACE_ID))
        .isEqualTo("KorService2");
    UUID sourceId =
        jdbcTemplate.queryForObject(
            "select id from public.tour_place_sources where place_id=?", UUID.class, SEED_PLACE_ID);
    assertThat(
            jdbcTemplate.queryForList(
                "select normalized_entity_type from public.tour_api_operation_provenance where normalized_row_id=?",
                String.class,
                SEED_PLACE_ID))
        .containsExactly("tour_places");
    assertThat(
            jdbcTemplate.queryForList(
                "select normalized_entity_type from public.tour_api_operation_provenance where normalized_row_id=?",
                String.class,
                sourceId))
        .containsExactly("tour_place_sources");
  }

  @Test
  void 같은_batch에서_동일_contentid를_여러번_전달해도_자연키_충돌없이_업데이트_후_스킵한다() {
    insertLegacyPlaceWithoutSource(SEED_PLACE_ID, "126435", "한국관광공사", "TourAPI 국문 관광정보", "구성산일출봉");
    PlaceListWrite write = write(place("126435", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW.plusSeconds(1));

    var result = repository.upsert(new PlaceListUpsertCommand(List.of(write, write)));

    assertThat(result.inserted()).isEqualTo(0);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select name from public.tour_places where id=?", String.class, SEED_PLACE_ID))
        .isEqualTo("성산일출봉");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_provider from public.tour_places where id=?",
                String.class,
                SEED_PLACE_ID))
        .isEqualTo("tour-api");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_service from public.tour_places where id=?",
                String.class,
                SEED_PLACE_ID))
        .isEqualTo("KorService2");
  }

  @Test
  void snapshot_run_fingerprint가_불일치하면_place_source_provenance를_모두_rollback한다() {
    String mismatched = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    assertThatThrownBy(
            () -> repository.upsert(command(place("100", "성산일출봉"), RUN, SNAPSHOT, mismatched, NOW)))
        .isInstanceOf(PlaceListImportException.class);

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isZero();
  }

  @Test
  void 새_snapshot은_같은_UUID에_변경값과_lineage를_update하고_snapshot별_provenance를_보존한다() {
    repository.upsert(command(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW));
    UUID placeId = jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class);
    UUID sourceId =
        jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class);
    jdbcTemplate.update(
        "update public.data_import_runs set status='succeeded', finished_at=? where id=?",
        Timestamp.from(NOW.plusSeconds(1)),
        RUN);
    UUID nextRun = UUID.fromString("26000000-0000-0000-0000-000000000003");
    UUID nextSnapshot = UUID.fromString("26000000-0000-0000-0000-000000000004");
    String nextHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    insertRunAndSnapshot(nextRun, nextSnapshot, nextHash);

    var updated =
        repository.upsert(
            command(place("100", "성산봉"), nextRun, nextSnapshot, nextHash, NOW.plusSeconds(2)));

    assertThat(updated.updated()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_places", UUID.class))
        .isEqualTo(placeId);
    assertThat(jdbcTemplate.queryForObject("select id from public.tour_place_sources", UUID.class))
        .isEqualTo(sourceId);
    assertThat(jdbcTemplate.queryForObject("select name from public.tour_places", String.class))
        .isEqualTo("성산봉");
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_places", UUID.class))
        .isEqualTo(nextSnapshot);
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_snapshot_id from public.tour_place_sources", UUID.class))
        .isEqualTo(nextSnapshot);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void area_location_keyword_stay의_같은_contentid는_한_place_source와_operation별_불변_provenance로_병합한다() {
    repository.upsert(command(place("100", "성산일출봉"), RUN, SNAPSHOT, HASH, NOW));
    List<String> operations = List.of("locationBasedList2", "searchKeyword2", "searchStay2");
    for (int index = 0; index < operations.size(); index++) {
      String operation = operations.get(index);
      UUID run = UUID.fromString("75000000-0000-0000-0000-0000000000" + (10 + index));
      UUID snapshot = UUID.fromString("75000000-0000-0000-0000-0000000000" + (20 + index));
      String hash = Integer.toString(index + 3).repeat(64);
      insertRunAndSnapshot(run, snapshot, hash, operation, "jeju");
      PlaceListWrite write =
          write(place("100", "성산일출봉"), run, snapshot, hash, NOW.plusSeconds(index + 1), operation);
      if (operation.equals("searchKeyword2")) {
        write =
            new PlaceListWrite(
                write.place(),
                write.seenAt(),
                write.lineage(),
                List.of(new PlaceAliasWrite("성산 맛집", "성산 맛집")));
      }
      repository.upsert(new PlaceListUpsertCommand(List.of(write)));
    }

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.place_aliases", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(9);
    assertThat(
            jdbcTemplate.queryForList(
                "select distinct operation_key from public.tour_api_operation_provenance where normalized_entity_type='tour_places' order by operation_key",
                String.class))
        .containsExactly("areaBasedList2", "locationBasedList2", "searchKeyword2", "searchStay2");
  }

  @Test
  void keyword_NFC_alias는_source_operation_fingerprint와_함께_exact_replay에서_xmin이_불변이다() {
    UUID run = UUID.fromString("75000000-0000-0000-0000-000000000030");
    UUID snapshot = UUID.fromString("75000000-0000-0000-0000-000000000031");
    String hash = "d".repeat(64);
    insertRunAndSnapshot(run, snapshot, hash, "searchKeyword2", "jeju");
    PlaceListWrite write =
        new PlaceListWrite(
            place("100", "성산일출봉"),
            NOW,
            new PlaceLineage("searchKeyword2", hash, snapshot, run),
            List.of(new PlaceAliasWrite("성산 맛집", "성산 맛집")));
    PlaceListUpsertCommand command = new PlaceListUpsertCommand(List.of(write));

    repository.upsert(command);
    Map<String, Object> before =
        jdbcTemplate.queryForMap(
            """
            select p.xmin::text place_xmin, s.xmin::text source_xmin, a.xmin::text alias_xmin
            from public.tour_places p
            join public.tour_place_sources s on s.place_id=p.id
            join public.place_aliases a on a.place_id=p.id
            """);
    repository.upsert(command);

    assertThat(
            jdbcTemplate.queryForMap(
                """
                select p.xmin::text place_xmin, s.xmin::text source_xmin, a.xmin::text alias_xmin
                from public.tour_places p
                join public.tour_place_sources s on s.place_id=p.id
                join public.place_aliases a on a.place_id=p.id
                """))
        .isEqualTo(before);
    assertThat(
            jdbcTemplate.queryForList(
                """
                select operation_key, request_fingerprint, source_snapshot_id
                from public.tour_api_operation_provenance
                where normalized_entity_type='place_aliases'
                """))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("operation_key", "searchKeyword2")
                    .containsEntry("request_fingerprint", hash)
                    .containsEntry("source_snapshot_id", snapshot));
  }

  @Test
  void 같은_watermark의_다른_manifest는_normalized_write와_run_checkpoint를_변경하지_않는다() {
    UUID run = UUID.fromString("75000000-0000-0000-0000-000000000040");
    UUID snapshot = UUID.fromString("75000000-0000-0000-0000-000000000041");
    String hash = "e".repeat(64);
    String scope = "jeju:manifest-conflict";
    insertRunAndSnapshot(run, snapshot, hash, "searchStay2", scope);
    insertCheckpoint("searchStay2", scope, "a".repeat(64), NOW);
    DiscoveryCommitCommand command =
        commitCommand(run, snapshot, hash, "searchStay2", scope, 0, NOW, "b".repeat(64));

    assertThatThrownBy(() -> discoveryCommitter.commit(command))
        .isInstanceOf(DiscoveryImportException.class);

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, run))
        .isEqualTo("running");
    assertThat(
            jdbcTemplate.queryForObject(
                "select version from public.data_import_checkpoints where source_operation='searchStay2' and scope_key=?",
                Long.class,
                scope))
        .isZero();
  }

  @Test
  void
      stale_checkpoint_partial_failure는_place_source_alias_provenance와_terminal_run을_모두_rollback한다() {
    UUID run = UUID.fromString("75000000-0000-0000-0000-000000000050");
    UUID snapshot = UUID.fromString("75000000-0000-0000-0000-000000000051");
    String hash = "f".repeat(64);
    String scope = "jeju:rollback";
    insertRunAndSnapshot(run, snapshot, hash, "searchKeyword2", scope);
    insertCheckpoint("searchKeyword2", scope, "uninitialized", Instant.EPOCH);
    DiscoveryCommitCommand command =
        commitCommand(run, snapshot, hash, "searchKeyword2", scope, 1, NOW, "9".repeat(64));

    assertThatThrownBy(() -> discoveryCommitter.commit(command))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.place_aliases", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from public.data_import_runs where id=?", String.class, run))
        .isEqualTo("running");
  }

  @Test
  void concurrent_location_keyword는_한_place_source로_직렬화하고_각_operation_lineage를_보존한다()
      throws Exception {
    UUID locationRun = UUID.fromString("75000000-0000-0000-0000-000000000060");
    UUID locationSnapshot = UUID.fromString("75000000-0000-0000-0000-000000000061");
    UUID keywordRun = UUID.fromString("75000000-0000-0000-0000-000000000062");
    UUID keywordSnapshot = UUID.fromString("75000000-0000-0000-0000-000000000063");
    String locationHash = "6".repeat(64);
    String keywordHash = "7".repeat(64);
    insertRunAndSnapshot(locationRun, locationSnapshot, locationHash, "locationBasedList2", "jeju");
    insertRunAndSnapshot(keywordRun, keywordSnapshot, keywordHash, "searchKeyword2", "jeju");
    PlaceListWrite location =
        write(
            place("100", "성산일출봉"),
            locationRun,
            locationSnapshot,
            locationHash,
            NOW,
            "locationBasedList2");
    PlaceListWrite keyword =
        new PlaceListWrite(
            place("100", "성산일출봉"),
            NOW.plusSeconds(1),
            new PlaceLineage("searchKeyword2", keywordHash, keywordSnapshot, keywordRun),
            List.of(new PlaceAliasWrite("성산 맛집", "성산 맛집")));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return repository.upsert(new PlaceListUpsertCommand(List.of(location)));
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return repository.upsert(new PlaceListUpsertCommand(List.of(keyword)));
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(
            jdbcTemplate.queryForObject("select count(*) from public.tour_places", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_place_sources", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.tour_api_operation_provenance", Integer.class))
        .isEqualTo(5);
  }

  @Test
  void keyword_alias는_RLS_ACL과_active_lookup_index를_사용한다() {
    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.place_aliases'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('anon','public.place_aliases','select') or has_table_privilege('authenticated','public.place_aliases','select')",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('service_role','public.place_aliases','insert')",
                Boolean.class))
        .isTrue();
    jdbcTemplate.execute("set local enable_seqscan=off");
    assertThat(
            jdbcTemplate.queryForList(
                "explain (costs off) select place_id from public.place_aliases where normalized_alias='성산 맛집' and alias_type='keyword' and tombstoned_at is null",
                String.class))
        .anySatisfy(plan -> assertThat(plan).contains("idx_place_aliases_keyword_lookup_active"));
  }

  private static PlaceListUpsertCommand command(
      TourPlace place, UUID run, UUID snapshot, String hash, Instant seenAt) {
    return new PlaceListUpsertCommand(List.of(write(place, run, snapshot, hash, seenAt)));
  }

  private static PlaceListWrite write(
      TourPlace place, UUID run, UUID snapshot, String hash, Instant seenAt) {
    return write(place, run, snapshot, hash, seenAt, "areaBasedList2");
  }

  private static PlaceListWrite write(
      TourPlace place, UUID run, UUID snapshot, String hash, Instant seenAt, String operation) {
    return new PlaceListWrite(place, seenAt, new PlaceLineage(operation, hash, snapshot, run));
  }

  private static TourPlace place(String contentId, String title) {
    return placeWithCategory(contentId, title, "12", "VE");
  }

  private static TourPlace placeWithCategory(
      String contentId, String title, String contentTypeId, String lclsSystm1) {
    return new TourPlace(
        contentId,
        contentTypeId,
        title,
        126.941516,
        33.458111,
        "제주특별자치도 서귀포시",
        "성산읍",
        "https://images.example.test/place.jpg",
        "https://images.example.test/thumb.jpg",
        "50",
        "50130",
        lclsSystm1,
        "VE01",
        "VE0101",
        NOW.minusSeconds(60));
  }

  private void insertRunAndSnapshot(UUID run, UUID snapshot, String hash) {
    insertRunAndSnapshot(run, snapshot, hash, "areaBasedList2", "jeju");
  }

  private void insertRunAndSnapshot(
      UUID run, UUID snapshot, String hash, String operation, String scope) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tour_api', 'fixture', ?, '2026', 'running', ?,
                  'place-list-v1', 'schema-v1', 'full', ?, ?, ?, 'tour-api', 'KorService2')
        """,
        run,
        operation,
        Timestamp.from(NOW),
        scope,
        hash,
        "issue-26-" + run);
    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, 'tour-api', 'KorService2', ?, ?, ?, '1', ?,
                  'place-list-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1',
                  'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        operation,
        scope,
        hash,
        Timestamp.from(NOW),
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        Timestamp.from(NOW));
  }

  private void insertCheckpoint(
      String operation, String scope, String manifest, Instant watermark) {
    jdbcTemplate.update(
        """
        insert into public.data_import_checkpoints (
          source_provider, source_service, source_operation, scope_key,
          checkpoint, source_watermark_at
        ) values ('tour-api','KorService2',?,?,jsonb_build_object('manifest',?,'pageCount',0),?)
        """,
        operation,
        scope,
        manifest,
        Timestamp.from(watermark));
  }

  private static DiscoveryCommitCommand commitCommand(
      UUID run,
      UUID snapshot,
      String hash,
      String operation,
      String scope,
      long expectedVersion,
      Instant watermark,
      String manifest) {
    PlaceListWrite write =
        new PlaceListWrite(
            place("100", "성산일출봉"),
            NOW,
            new PlaceLineage(operation, hash, snapshot, run),
            operation.equals("searchKeyword2")
                ? List.of(new PlaceAliasWrite("성산 맛집", "성산 맛집"))
                : List.of());
    return new DiscoveryCommitCommand(
        new ImportRunLease(run, UUID.fromString("75000000-0000-0000-0000-000000000099"), 1),
        new ImportRunScope("tour-api", "KorService2", operation, scope),
        expectedVersion,
        watermark,
        manifest,
        1,
        List.of(write),
        new ImportRunCounts(1, 1, 0, 0, 0, 0, 0, 0));
  }

  private void insertLegacyPlaceWithoutSource(
      UUID placeId, String contentId, String sourceProvider, String sourceService, String name) {
    insertLegacyLineage(LEGACY_RUN, LEGACY_SNAPSHOT, sourceProvider, sourceService);

    UUID placeRunId = LEGACY_RUN;
    UUID placeSnapshotId = LEGACY_SNAPSHOT;
    jdbcTemplate.update(
        """
        insert into public.tour_places (
          id, external_place_id, content_id, content_type_id, name, normalized_name,
          category, region_code, address, address_detail, location, source_provider, source_service,
          source_snapshot_id, import_run_id, last_seen_at
        ) values (
          ?, ?, ?, '12', ?, ?, 'attraction', '50130', '제주', '성산읍',
          ST_SetSRID(ST_MakePoint(126.5, 33.5),4326)::geography, ?, ?, ?, ?, ?)
        """,
        placeId,
        contentId,
        contentId,
        name,
        name,
        sourceProvider,
        sourceService,
        placeSnapshotId,
        placeRunId,
        Timestamp.from(NOW.minusSeconds(120)));
  }

  private void insertLegacyLineage(
      UUID run, UUID snapshot, String sourceProvider, String sourceService) {
    jdbcTemplate.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint,
          idempotency_key, source_provider, source_service
        ) values (?, 'tour_api', 'fixture-legacy', 'areaBasedList2', '2026', 'running', ?,
                  'place-list-v1', 'schema-v1', 'full', 'jeju', ?, ?, ?, ?)
        """,
        run,
        Timestamp.from(NOW),
        LEGACY_HASH,
        "issue-26-legacy-" + run,
        sourceProvider,
        sourceService);

    jdbcTemplate.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash,
          request_metadata_redacted, raw_payload, payload_size_bytes, redaction_version,
          payload_format, initial_parse_status, parse_status, parsed_at
        ) values (?, ?, ?, ?, 'areaBasedList2', 'jeju', ?, '1', ?,
                  'place-list-v1', ?, '{}'::jsonb, '{}'::jsonb, 2, 'test-v1',
                  'JSON', 'parsed', 'parsed', ?)
        """,
        snapshot,
        run,
        sourceProvider,
        sourceService,
        LEGACY_HASH,
        Timestamp.from(NOW),
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbcTemplate.update("delete from public.tour_api_operation_provenance");
    jdbcTemplate.update("delete from public.place_aliases");
    jdbcTemplate.update("delete from public.tour_place_sources");
    jdbcTemplate.update("delete from public.tour_places");
    jdbcTemplate.update("delete from public.external_api_snapshots");
    jdbcTemplate.update("delete from public.data_import_runs");
  }
}
