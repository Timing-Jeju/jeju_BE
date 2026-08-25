package com.timingjeju.api.domain.places.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.domain.places.config.PlaceNearbyStopsProperties;
import com.timingjeju.api.domain.places.dto.response.PlaceDetailResponse;
import com.timingjeju.api.domain.places.exception.PlaceDetailException;
import com.timingjeju.api.domain.places.model.PlaceDetailNearbyStopRow;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import com.timingjeju.api.domain.places.service.PlaceDetailService;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcPublicPlaceDetailRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID PLACE = UUID.fromString("33000000-0000-0000-0000-000000000001");
  private static final UUID NO_DETAIL = UUID.fromString("33000000-0000-0000-0000-000000000002");
  private static final UUID DELETED = UUID.fromString("33000000-0000-0000-0000-000000000003");
  private static final UUID STALE_BOOLEAN = UUID.fromString("33000000-0000-0000-0000-000000000004");
  private static final UUID STALE_AT = UUID.fromString("33000000-0000-0000-0000-000000000005");
  private static final UUID TOMBSTONED = UUID.fromString("33000000-0000-0000-0000-000000000006");
  private static final UUID FUTURE_FRESH = UUID.fromString("33000000-0000-0000-0000-000000000007");
  private static final UUID USER_A = UUID.fromString("33000000-0000-0000-0000-000000000011");
  private static final UUID USER_B = UUID.fromString("33000000-0000-0000-0000-000000000012");

  @Autowired private JdbcPublicPlaceDetailRepository repository;
  @Autowired private PlaceDetailService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate namedJdbc;

  @BeforeEach
  void setUp() {
    insertUser(USER_A);
    insertUser(USER_B);
    insertPlace(PLACE, "detail-active", null);
    insertPlace(NO_DETAIL, "detail-empty", null);
    insertPlace(DELETED, "detail-deleted", Instant.parse("2026-08-21T00:00:00Z"));
    insertPlace(STALE_BOOLEAN, "detail-stale-boolean", null);
    insertPlace(STALE_AT, "detail-stale-at", null);
    insertPlace(TOMBSTONED, "detail-tombstoned", null);
    insertPlace(FUTURE_FRESH, "detail-future-fresh", null);
    jdbc.update(
        "update public.tour_places set stale=true, stale_at=now() where id=?", STALE_BOOLEAN);
    jdbc.update("update public.tour_places set stale=false, stale_at=now() where id=?", STALE_AT);
    jdbc.update("update public.tour_places set tombstoned_at=now() where id=?", TOMBSTONED);
    jdbc.update(
        "update public.tour_places set stale=false, stale_at=now()+interval '1 hour' where id=?",
        FUTURE_FRESH);
  }

  @Test
  void detail_image_item이_없어도_active_place는_null_empty_snapshot으로_조회한다() {
    PlaceDetailSnapshot snapshot = repository.find(NO_DETAIL, Optional.empty()).orElseThrow();

    assertThat(snapshot.overview()).isNull();
    assertThat(snapshot.phone()).isNull();
    assertThat(snapshot.operatingHoursText()).isNull();
    assertThat(snapshot.images()).isEmpty();
    assertThat(snapshot.saved()).isFalse();
    assertThat(snapshot.memo()).isNull();
    assertThat(snapshot.tags()).isEmpty();
  }

  @Test
  void active_image는_display_order_id로_정렬하고_tombstone을_제외하며_overnight_text를_보존한다() {
    jdbc.update(
        """
        insert into public.place_details(
          place_id,phone,homepage_url,operating_hours_text,closed_days_text,parking_text,
          admission_fee_text,source_provider,source_service)
        values (?, '064-000-0001', 'https://www.example.test/place', '22:00~02:00',
                '화요일 휴무', '주차 가능', '성인 5,000원', 'fixture', 'detail-test')
        """,
        PLACE);
    insertImage("33000000-0000-0000-0000-000000000103", -1, "tombstoned", Instant.now());
    insertImage("33000000-0000-0000-0000-000000000102", 0, "second", null);
    insertImage("33000000-0000-0000-0000-000000000101", 0, "first", null);

    PlaceDetailResponse response = service.detail(PLACE, Optional.empty());

    assertThat(response.operations().operatingHoursText()).isEqualTo("22:00~02:00");
    assertThat(response.operationsSummary()).isEqualTo("22:00~02:00 · 화요일 휴무 · 주차 가능 · 성인 5,000원");
    assertThat(response.images())
        .extracting(image -> image.url().toString())
        .containsExactly(
            "https://images.example.test/first.jpg", "https://images.example.test/second.jpg");
    assertThat(response.thumbnailUrl().toString())
        .isEqualTo("https://images.example.test/first-thumb.jpg");
    assertThat(response.nearbyStops()).isEmpty();
  }

  @Test
  void anonymous과_authenticated_saved_projection은_current_user별로_격리한다() {
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['동쪽','필수'])",
        USER_A,
        PLACE,
        "오전 방문");
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['비공개'])",
        USER_B,
        NO_DETAIL,
        "다른 사용자");

    PlaceDetailSnapshot anonymous = repository.find(PLACE, Optional.empty()).orElseThrow();
    PlaceDetailSnapshot mine = repository.find(PLACE, Optional.of(USER_A)).orElseThrow();
    PlaceDetailSnapshot other = repository.find(PLACE, Optional.of(USER_B)).orElseThrow();

    assertThat(anonymous.saved()).isFalse();
    assertThat(anonymous.memo()).isNull();
    assertThat(anonymous.tags()).isEmpty();
    assertThat(mine.saved()).isTrue();
    assertThat(mine.memo()).isEqualTo("오전 방문");
    assertThat(mine.tags()).containsExactly("필수", "동쪽");
    assertThat(other.saved()).isFalse();
    assertThat(other.memo()).isNull();
    assertThat(other.tags()).isEmpty();
  }

  @Test
  void source_deleted와_missing_place는_공개상세에서_찾을_수_없다() {
    assertThat(repository.find(DELETED, Optional.empty())).isEmpty();
    assertThat(repository.find(new UUID(0, 0), Optional.empty())).isEmpty();
  }

  @Test
  void stale_boolean_effective_stale_at_tombstone은_404이고_future_stale_at은_active다() {
    assertThat(repository.find(STALE_BOOLEAN, Optional.empty())).isEmpty();
    assertThat(repository.find(STALE_AT, Optional.empty())).isEmpty();
    assertThat(repository.find(TOMBSTONED, Optional.empty())).isEmpty();
    assertThat(repository.find(FUTURE_FRESH, Optional.empty())).isPresent();
  }

  @Test
  void active_image_21개는_DB에서_stable_first20만_읽고_thumbnail은_first다() {
    for (int index = 0; index < 21; index++) {
      insertImage(
          "33000000-0000-0000-0000-" + String.format("%012d", 200 + index),
          index / 2,
          String.format("bounded-%02d", index),
          null);
    }

    PlaceDetailResponse response = service.detail(PLACE, Optional.empty());

    assertThat(response.images()).hasSize(20);
    assertThat(response.images())
        .extracting(image -> image.url().toString())
        .containsExactly(
            java.util.stream.IntStream.range(0, 20)
                .mapToObj(
                    index -> String.format("https://images.example.test/bounded-%02d.jpg", index))
                .toArray(String[]::new));
    assertThat(response.thumbnailUrl().toString())
        .isEqualTo("https://images.example.test/bounded-00-thumb.jpg");
  }

  @Test
  void 실제_stay_resolver의_minutes_source_version_effectiveAt_updatedAt을_상세에_투영한다() {
    Instant effectiveAt = Instant.parse("2026-08-20T01:00:00Z");
    Instant updatedAt = Instant.parse("2026-08-20T01:00:05Z");
    jdbc.update(
        "insert into public.place_stay_policy_versions(version,status,payload_hash,effective_at,imported_at) values ('detail-v1','active',repeat('a',64),?,?)",
        Timestamp.from(effectiveAt),
        Timestamp.from(updatedAt));
    jdbc.update(
        "insert into public.place_stay_policies(version,scope,category,minutes,source,updated_at) values ('detail-v1','category_default','VE',75,'app_curation',?)",
        Timestamp.from(updatedAt));

    PlaceDetailResponse response = service.detail(PLACE, Optional.empty());

    assertThat(response.recommendedStayMinutes()).isEqualTo(75);
    assertThat(response.recommendedStaySource()).isEqualTo("category_default");
    assertThat(response.recommendedStayPolicyVersion()).isEqualTo("detail-v1");
    assertThat(response.recommendedStayEffectiveAt()).isEqualTo(effectiveAt);
    assertThat(response.recommendedStayUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void 주변정류장은_fresh를_먼저_정렬하고_stale_fallback으로_전체5개를_채운다() {
    Instant now = Instant.parse("2026-08-21T06:00:00Z");
    UUID freshFar = stop(101, 300, 4, now.plusSeconds(7200), null);
    UUID freshTieWalkEight = stop(102, 100, 8, now.plusSeconds(7200), null);
    UUID freshTieWalkThree = stop(103, 100, 3, now.plusSeconds(10800), now.plusSeconds(3600));
    UUID staleEquality = stop(104, 50, 1, now.plusSeconds(7200), now);
    UUID staleThirty = stop(105, 30, 2, now.minusSeconds(1), null);
    UUID staleTen = stop(106, 10, 5, now.minusSeconds(2), null);
    UUID staleTwenty = stop(107, 20, 4, now.minusSeconds(3), null);

    List<PlaceDetailNearbyStopRow> stops =
        repositoryAt(now, 500).find(PLACE, Optional.empty()).orElseThrow().nearbyStops();

    assertThat(stops)
        .extracting(PlaceDetailNearbyStopRow::stopId)
        .containsExactly(freshTieWalkThree, freshTieWalkEight, freshFar, staleTen, staleTwenty);
    assertThat(stops)
        .extracting(PlaceDetailNearbyStopRow::stale)
        .containsExactly(false, false, false, true, true);
    assertThat(stops.getFirst().expiresAt()).isEqualTo(now.plusSeconds(3600));
    assertThat(stops).noneMatch(stop -> stop.stopId().equals(staleEquality));
    assertThat(stops).noneMatch(stop -> stop.stopId().equals(staleThirty));
  }

  @Test
  void 주변정류장은_inclusive거리와_equal_expiry를_포함하고_lifecycle_outside를_제외한다() {
    Instant now = Instant.parse("2026-08-21T06:00:00Z");
    UUID fresh = stop(111, 499, 3, now.plusSeconds(1), null);
    UUID equality = stop(112, 500, 4, now.plusSeconds(3600), now);
    stop(113, 501, 1, now.plusSeconds(3600), null);
    UUID disabled = stop(114, 10, 1, now.plusSeconds(3600), null);
    UUID linkTombstoned = stop(115, 20, 1, now.plusSeconds(3600), null);
    UUID stopTombstoned = stop(116, 30, 1, now.plusSeconds(3600), null);
    UUID sourceDeleted = stop(117, 40, 1, now.plusSeconds(3600), null);
    jdbc.update(
        "update public.place_stop_links set enabled=false where place_id=? and stop_id=?",
        PLACE,
        disabled);
    jdbc.update(
        "update public.place_stop_links set enabled=false,tombstoned_at=? where place_id=? and stop_id=?",
        Timestamp.from(now),
        PLACE,
        linkTombstoned);
    jdbc.update(
        "update public.bus_stops set tombstoned_at=? where id=?",
        Timestamp.from(now),
        stopTombstoned);
    jdbc.update(
        "update public.bus_stops set source_deleted_at=? where id=?",
        Timestamp.from(now),
        sourceDeleted);

    List<PlaceDetailNearbyStopRow> stops =
        repositoryAt(now, 500).find(PLACE, Optional.empty()).orElseThrow().nearbyStops();

    assertThat(stops).extracting(PlaceDetailNearbyStopRow::stopId).containsExactly(fresh, equality);
    assertThat(stops).extracting(PlaceDetailNearbyStopRow::stale).containsExactly(false, true);
    assertThat(stops.get(1).expiresAt()).isEqualTo(now);
  }

  @Test
  void stop_staleAt이_더_늦은_link_observedAt보다_과거여도_상세은_effective_expiry로_반환한다() {
    Instant linkObservedAt = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    Instant olderStopStaleAt = linkObservedAt.minusSeconds(3600);
    UUID stop =
        stop(118, 100, 2, linkObservedAt, linkObservedAt.plusSeconds(7200), olderStopStaleAt);

    PlaceDetailResponse response = service.detail(PLACE, Optional.empty());

    assertThat(response.nearbyStops())
        .singleElement()
        .satisfies(
            nearby -> {
              assertThat(nearby.stopId()).isEqualTo(stop);
              assertThat(nearby.observedAt()).isEqualTo(linkObservedAt);
              assertThat(nearby.expiresAt()).isEqualTo(olderStopStaleAt);
              assertThat(nearby.stale()).isTrue();
            });
  }

  @Test
  void legacy_DB의_128_code_point초과_provider는_raw값없이_typed503으로_닫는다() {
    UUID stop = stop(119, 100, 2, Instant.now().plusSeconds(7200), null);
    String invalidProvider = "🍊".repeat(129);
    jdbc.update(
        "update public.place_stop_links set source_provider=? where place_id=? and stop_id=?",
        invalidProvider,
        PLACE,
        stop);

    Throwable failure = catchThrowable(() -> service.detail(PLACE, Optional.empty()));

    assertThat(failure)
        .isInstanceOf(PlaceDetailException.class)
        .extracting("code")
        .isEqualTo("PLACE_DATA_UNAVAILABLE");
    assertThat(failure).hasMessageNotContaining(invalidProvider);
  }

  @Test
  void 상세_query는_place_PK와_image_order_index_plan을_사용한다() {
    jdbc.execute("set local enable_seqscan=off");
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                """
                explain select p.id
                from public.tour_places p
                left join lateral (
                  select candidate.id, candidate.display_order
                  from public.place_images candidate
                  where candidate.place_id=p.id and candidate.tombstoned_at is null
                  order by candidate.display_order asc nulls last, candidate.id asc
                  limit 20
                ) image on true
                where p.id='33000000-0000-0000-0000-000000000001'::uuid
                  and p.source_deleted_at is null
                  and p.tombstoned_at is null
                  and p.stale=false
                  and (p.stale_at is null or p.stale_at > now())
                order by image.display_order asc nulls last, image.id asc nulls last
                """,
                String.class));

    assertThat(plan)
        .contains("tour_places_pkey")
        .contains("Limit")
        .matches("(?s).*(idx_place_images_place_order|uq_place_images_source_url_key).*");
  }

  private void insertUser(UUID userId) {
    jdbc.update("insert into auth.users(id,email) values (?,?)", userId, userId + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)",
        userId,
        userId + "@example.test");
  }

  private void insertPlace(UUID id, String contentId, Instant sourceDeletedAt) {
    jdbc.update(
        """
        insert into public.tour_places(
          id,content_id,name,normalized_name,category,region_code,location,overview,
          source_provider,source_service,source_modified_at,source_deleted_at)
        values (?,?,'성산일출봉','성산일출봉','VE','seongsan',
          ST_SetSRID(ST_MakePoint(126.941,33.458),4326)::geography,null,
          'fixture','detail-test',?,?)
        """,
        id,
        contentId,
        Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        sourceDeletedAt == null ? null : Timestamp.from(sourceDeletedAt));
  }

  private void insertImage(String id, int displayOrder, String name, Instant tombstonedAt) {
    jdbc.update(
        """
        insert into public.place_images(
          id,place_id,image_url,thumbnail_url,display_order,source_provider,source_service,
          tombstoned_at)
        values (?::uuid,?, ?, ?, ?, 'admin_upload','detail-test',?)
        """,
        id,
        PLACE,
        "https://images.example.test/" + name + ".jpg",
        "https://images.example.test/" + name + "-thumb.jpg",
        displayOrder,
        tombstonedAt == null ? null : Timestamp.from(tombstonedAt));
  }

  private UUID stop(
      int suffix, int distanceMeters, int walkMinutes, Instant linkExpiresAt, Instant staleAt) {
    return stop(
        suffix,
        distanceMeters,
        walkMinutes,
        Instant.parse("2026-08-21T00:00:00Z"),
        linkExpiresAt,
        staleAt);
  }

  private UUID stop(
      int suffix,
      int distanceMeters,
      int walkMinutes,
      Instant linkObservedAt,
      Instant linkExpiresAt,
      Instant staleAt) {
    UUID stopId = UUID.fromString("66000000-0000-0000-0000-" + String.format("%012d", suffix));
    jdbc.update(
        "insert into public.bus_stops(id,node_id,node_name,location,source_provider,source_service,city_code,last_seen_at,stale_at) values (?,?,?,ST_SetSRID(ST_MakePoint(126.941,33.458),4326)::geography,'fixture','fixture','39',?,?)",
        stopId,
        stopId.toString(),
        "정류장-" + suffix,
        Timestamp.from(Instant.parse("2026-08-21T00:00:00Z")),
        staleAt == null ? null : Timestamp.from(staleAt));
    jdbc.update(
        "insert into public.place_stop_links(place_id,stop_id,distance_meters,walk_minutes,link_method,enabled,source_provider,observed_at,expires_at) values (?,?,?,?, 'spatial_radius', true, 'postgis:tago', ?, ?)",
        PLACE,
        stopId,
        distanceMeters,
        walkMinutes,
        Timestamp.from(linkObservedAt),
        Timestamp.from(linkExpiresAt));
    return stopId;
  }

  private JdbcPublicPlaceDetailRepository repositoryAt(Instant now, int maxDistanceMeters) {
    return new JdbcPublicPlaceDetailRepository(
        namedJdbc,
        new PlaceNearbyStopsProperties(maxDistanceMeters),
        Clock.fixed(now, ZoneOffset.UTC));
  }
}
