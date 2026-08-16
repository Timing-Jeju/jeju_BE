package com.timingjeju.api.global.placestop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.placestop.PlaceStopLinkBatch;
import com.timingjeju.api.application.placestop.PlaceStopLinkConflictException;
import com.timingjeju.api.application.placestop.PlaceStopLinkPolicy;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcPlaceStopLinkRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID PLACE = UUID.fromString("37000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_PLACE = UUID.fromString("37000000-0000-0000-0000-000000000002");
  private static final UUID STOP_A = UUID.fromString("37000000-0000-0000-0000-000000000011");
  private static final UUID STOP_B = UUID.fromString("37000000-0000-0000-0000-000000000012");
  private static final UUID STOP_C = UUID.fromString("37000000-0000-0000-0000-000000000013");
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-16T06:00:00Z");
  private static final String FINGERPRINT =
      "3737373737373737373737373737373737373737373737373737373737373737";
  private static final PlaceStopLinkPolicy POLICY =
      new PlaceStopLinkPolicy(500, 2, Duration.ofHours(24), Duration.ofHours(6));

  @Autowired private JdbcPlaceStopLinkRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    insertPlace(PLACE, 126.5, 33.5);
    insertPlace(OTHER_PLACE, 126.7, 33.5);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from public.place_stop_link_scope_states");
    jdbcTemplate.update("delete from public.place_stop_links");
    jdbcTemplate.update("delete from public.bus_stops");
    jdbcTemplate.update("delete from public.tour_places");
  }

  @Test
  void 반경내_후보를_nearest_N과_distance_stopId_tie순으로_멱등저장한다() {
    insertStopAtDistance(STOP_B, 100, OBSERVED_AT);
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    insertStopAtDistance(STOP_C, 200, OBSERVED_AT);

    var first = repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);
    var replay = repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);
    List<UUID> stopIds =
        jdbcTemplate.queryForList(
            "select stop_id from public.place_stop_links where place_id=? order by distance_meters, stop_id",
            UUID.class,
            PLACE);

    assertThat(first.upserted()).isEqualTo(2);
    assertThat(replay.replayed()).isTrue();
    assertThat(stopIds).containsExactly(STOP_A, STOP_B);
  }

  @Test
  void 정확히_500m는_포함하고_500m초과는_제외한다() {
    insertStopAtDistance(STOP_A, 500, OBSERVED_AT);
    insertStopAtDistance(STOP_B, 500.01, OBSERVED_AT);

    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);

    assertThat(linkedStops(PLACE)).containsExactly(STOP_A);
  }

  @Test
  void link_expiry는_policy와_stop_freshness_cutoff중_이른값이다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT.minus(Duration.ofHours(5)));

    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);

    assertThat(
            jdbcTemplate.queryForObject(
                "select expires_at from public.place_stop_links where place_id=? and stop_id=?",
                Instant.class,
                PLACE,
                STOP_A))
        .isEqualTo(OBSERVED_AT.plus(Duration.ofHours(1)));
  }

  @Test
  void freshness_cutoff가_지난_active_link는_enabled를_유지하고_stale_fallback으로_조회된다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);

    var candidates = repository.findEligible(PLACE, 500, 3, OBSERVED_AT.plus(Duration.ofHours(7)));

    assertThat(candidates)
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.stopId()).isEqualTo(STOP_A);
              assertThat(candidate.fresh()).isFalse();
            });
    assertThat(
            jdbcTemplate.queryForObject(
                "select enabled from public.place_stop_links where place_id=? and stop_id=?",
                Boolean.class,
                PLACE,
                STOP_A))
        .isTrue();
  }

  @Test
  void disabled_link와_link_or_stop_tombstone은_eligibility에서_제외된다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    insertStopAtDistance(STOP_B, 200, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);
    jdbcTemplate.update(
        "update public.place_stop_links set enabled=false where place_id=? and stop_id=?",
        PLACE,
        STOP_A);
    jdbcTemplate.update(
        "update public.bus_stops set tombstoned_at=? where id=?",
        Timestamp.from(OBSERVED_AT.plusSeconds(1)),
        STOP_B);

    assertThat(repository.findEligible(PLACE, 500, 3, OBSERVED_AT)).isEmpty();
  }

  @Test
  void complete의_0건_scope는_기존link를_tombstone하지만_partial은_보존한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);
    jdbcTemplate.update(
        "update public.bus_stops set source_deleted_at=? where id=?",
        Timestamp.from(OBSERVED_AT),
        STOP_A);

    repository.recompute(
        batchAt(
            Set.of(PLACE),
            false,
            OBSERVED_AT.plusSeconds(1),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        POLICY);
    assertThat(linkState(STOP_A)).containsExactly(true, false);

    repository.recompute(
        batchAt(
            Set.of(PLACE),
            true,
            OBSERVED_AT.plusSeconds(2),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
        POLICY);
    assertThat(linkState(STOP_A)).containsExactly(false, true);
  }

  @Test
  void 재등장한_source는_같은_PK를_active로_복구하고_provenance와_expiry를_갱신한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);
    jdbcTemplate.update(
        "update public.bus_stops set source_deleted_at=? where id=?",
        Timestamp.from(OBSERVED_AT),
        STOP_A);
    repository.recompute(
        batchAt(
            Set.of(PLACE),
            true,
            OBSERVED_AT.plusSeconds(1),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        POLICY);
    jdbcTemplate.update(
        "update public.bus_stops set source_deleted_at=null, last_seen_at=? where id=?",
        Timestamp.from(OBSERVED_AT.plusSeconds(2)),
        STOP_A);

    repository.recompute(
        batchAt(
            Set.of(),
            Set.of(STOP_A),
            true,
            OBSERVED_AT.plusSeconds(2),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
        POLICY);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.place_stop_links where place_id=? and stop_id=?",
                Integer.class,
                PLACE,
                STOP_A))
        .isEqualTo(1);
    assertThat(linkState(STOP_A)).containsExactly(true, false);
  }

  @Test
  void 동일_observedAt의_exact_replay만_허용하고_다른_fingerprint는_충돌한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);

    assertThatThrownBy(
            () ->
                repository.recompute(
                    batchAt(
                        Set.of(PLACE),
                        true,
                        OBSERVED_AT,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    POLICY))
        .isInstanceOf(PlaceStopLinkConflictException.class);
    assertThatThrownBy(
            () ->
                repository.recompute(
                    batchAt(
                        Set.of(PLACE),
                        false,
                        OBSERVED_AT.minusSeconds(1),
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    POLICY))
        .isInstanceOf(PlaceStopLinkConflictException.class);
  }

  @Test
  void 제주_범위를_벗어난_place_coordinate는_거부한다() {
    jdbcTemplate.update(
        "update public.tour_places set location=ST_SetSRID(ST_MakePoint(129.0,35.0),4326)::geography where id=?",
        PLACE);

    assertThatThrownBy(() -> repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("제주");
  }

  @Test
  void coordinate변경은_영향받은_place_scope만_재계산한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE, OTHER_PLACE), Set.of(), true), POLICY);
    jdbcTemplate.update(
        "update public.tour_places set location=ST_SetSRID(ST_MakePoint(126.7,33.5),4326)::geography, updated_at=? where id=?",
        Timestamp.from(OBSERVED_AT.plusSeconds(1)),
        PLACE);

    repository.recompute(
        batchAt(
            Set.of(PLACE),
            true,
            OBSERVED_AT.plusSeconds(1),
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        POLICY);

    assertThat(linkState(STOP_A)).containsExactly(false, true);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from public.place_stop_link_scope_states where place_id=?",
                Integer.class,
                OTHER_PLACE))
        .isEqualTo(1);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void lifecycle_check_partial_index_RLS와_query_plan을_고정한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.place_stop_links set expires_at=observed_at where place_id=?",
                    PLACE))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.place_stop_links set source_provider=' ' where place_id=?",
                    PLACE))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.place_stop_links set tombstoned_at=?, enabled=true where place_id=?",
                    Timestamp.from(OBSERVED_AT.plusSeconds(1)),
                    PLACE))
        .hasRootCauseInstanceOf(java.sql.SQLException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "select relrowsecurity from pg_class where oid='public.place_stop_links'::regclass",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('anon','public.place_stop_links','SELECT')",
                Boolean.class))
        .isFalse();
    jdbcTemplate.execute("set local enable_seqscan=off");
    String plan =
        String.join(
            "\n",
            jdbcTemplate.queryForList(
                "explain select stop_id from public.place_stop_links where place_id='"
                    + PLACE
                    + "' and enabled and tombstoned_at is null and distance_meters <= 500 order by expires_at desc, distance_meters, stop_id limit 3",
                String.class));
    assertThat(plan).contains("idx_place_stop_links_eligible");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void batch_중_scope_watermark_실패는_link와_marker를_모두_rollback한다() {
    insertStopAtDistance(STOP_A, 100, OBSERVED_AT);
    jdbcTemplate.update(
        "alter table public.place_stop_link_scope_states add constraint test_rollback check (false) not valid");

    try {
      assertThatThrownBy(() -> repository.recompute(batch(Set.of(PLACE), Set.of(), true), POLICY))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from public.place_stop_links", Integer.class))
          .isZero();
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from public.place_stop_link_scope_states", Integer.class))
          .isZero();
    } finally {
      jdbcTemplate.update(
          "alter table public.place_stop_link_scope_states drop constraint test_rollback");
    }
  }

  private PlaceStopLinkBatch batch(Set<UUID> places, Set<UUID> stops, boolean complete) {
    return new PlaceStopLinkBatch(
        places, stops, "postgis:tago", OBSERVED_AT, FINGERPRINT, complete);
  }

  private PlaceStopLinkBatch batchAt(
      Set<UUID> places, boolean complete, Instant observedAt, String fingerprint) {
    return batchAt(places, Set.of(), complete, observedAt, fingerprint);
  }

  private PlaceStopLinkBatch batchAt(
      Set<UUID> places, Set<UUID> stops, boolean complete, Instant observedAt, String fingerprint) {
    return new PlaceStopLinkBatch(places, stops, "postgis:tago", observedAt, fingerprint, complete);
  }

  private void insertPlace(UUID id, double longitude, double latitude) {
    jdbcTemplate.update(
        "insert into public.tour_places(id,name,normalized_name,category,location,source_provider,source_service,last_seen_at) values (?,?,'fixture','attraction',ST_SetSRID(ST_MakePoint(?,?),4326)::geography,'fixture','fixture',?)",
        id,
        "place-" + id,
        longitude,
        latitude,
        Timestamp.from(OBSERVED_AT));
  }

  private void insertStopAtDistance(UUID id, double meters, Instant lastSeenAt) {
    jdbcTemplate.update(
        "insert into public.bus_stops(id,node_id,node_name,location,source_provider,source_service,city_code,last_seen_at) values (?,?,'stop',ST_Project(ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,?,radians(90)),'fixture','fixture','39',?)",
        id,
        id.toString(),
        meters,
        Timestamp.from(lastSeenAt));
  }

  private List<UUID> linkedStops(UUID placeId) {
    return jdbcTemplate.queryForList(
        "select stop_id from public.place_stop_links where place_id=? order by stop_id",
        UUID.class,
        placeId);
  }

  private List<Boolean> linkState(UUID stopId) {
    return jdbcTemplate.queryForObject(
        "select enabled, tombstoned_at is not null from public.place_stop_links where place_id=? and stop_id=?",
        (rs, row) -> List.of(rs.getBoolean(1), rs.getBoolean(2)),
        PLACE,
        stopId);
  }
}
