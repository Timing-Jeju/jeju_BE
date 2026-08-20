package com.timingjeju.api.domain.places.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import com.timingjeju.api.domain.places.model.PlaceSearchPosition;
import com.timingjeju.api.domain.places.model.PlaceSearchRow;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPlaceSearchRepositoryIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID USER_A = UUID.fromString("32000000-0000-0000-0000-000000000001");
  private static final UUID USER_B = UUID.fromString("32000000-0000-0000-0000-000000000002");
  private static final UUID PLACE_A = UUID.fromString("32000000-0000-0000-0000-000000000011");
  private static final UUID PLACE_B = UUID.fromString("32000000-0000-0000-0000-000000000012");
  private static final UUID PLACE_C = UUID.fromString("32000000-0000-0000-0000-000000000013");
  private static final UUID DELETED = UUID.fromString("32000000-0000-0000-0000-000000000014");

  @Autowired private JdbcPlaceSearchRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    insertUser(USER_A);
    insertUser(USER_B);
    insertPlace(
        PLACE_A,
        "content-a",
        "성산일출봉",
        "성산일출봉",
        "tourist_attraction",
        "seongsan",
        126.5,
        33.5,
        false,
        null);
    insertPlace(
        PLACE_B,
        "content-b",
        "성산_해변",
        "성산_해변",
        "tourist_attraction",
        "seongsan",
        126.51,
        33.5,
        true,
        null);
    insertPlace(
        PLACE_C, "content-c", "카페 100%", "카페 100%", "cafe", "jeju-si", 126.52, 33.5, false, null);
    insertPlace(
        DELETED,
        "content-deleted",
        "삭제 장소",
        "삭제 장소",
        "tourist_attraction",
        "seongsan",
        126.5,
        33.5,
        false,
        Instant.now());
    jdbc.update(
        "insert into public.place_aliases(place_id,alias,normalized_alias,alias_type) values (?,?,?,'user_query')",
        PLACE_A,
        "일출 명소",
        "일출 명소");
  }

  @Test
  void 이름과_alias를_검색하고_percent_underscore를_literal_bind로_처리한다() {
    assertThat(search("일출", null, null, Optional.empty()))
        .extracting(PlaceSearchRow::placeId)
        .containsExactly(PLACE_A);
    assertThat(search("%", null, null, Optional.empty()))
        .extracting(PlaceSearchRow::placeId)
        .containsExactly(PLACE_C);
    assertThat(search("_", null, null, Optional.empty()))
        .extracting(PlaceSearchRow::placeId)
        .containsExactly(PLACE_B);
  }

  @Test
  void category_region_active_lifecycle과_대표이미지_operations_freshness를_한_query로_투영한다() {
    UUID firstImage = UUID.fromString("32000000-0000-0000-0000-000000000101");
    UUID secondImage = UUID.fromString("32000000-0000-0000-0000-000000000102");
    insertImage(secondImage, PLACE_A, 0, "https://images.example.test/second.jpg", null);
    insertImage(firstImage, PLACE_A, 0, "https://images.example.test/first.jpg", null);
    insertImage(
        UUID.fromString("32000000-0000-0000-0000-000000000100"),
        PLACE_A,
        -1,
        "https://images.example.test/tombstoned.jpg",
        Instant.now());
    jdbc.update(
        "insert into public.place_details(place_id,operating_hours_text,closed_days_text,source_provider,source_service) values (?, '09:00~18:00', '월요일', 'fixture', 'places-test')",
        PLACE_A);

    List<PlaceSearchRow> rows = search(null, "tourist_attraction", "seongsan", Optional.empty());

    assertThat(rows).extracting(PlaceSearchRow::placeId).containsExactly(PLACE_A, PLACE_B);
    assertThat(rows.getFirst().thumbnailUrl()).isEqualTo("https://images.example.test/first.jpg");
    assertThat(rows.getFirst().operationsSummary()).isEqualTo("09:00~18:00 · 월요일");
    assertThat(rows.get(1).stale()).isTrue();
    assertThat(rows).extracting(PlaceSearchRow::placeId).doesNotContain(DELETED);
  }

  @Test
  void saved_projection과_savedOnly는_current_user별로_격리한다() {
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['동쪽','필수'])",
        USER_A,
        PLACE_A,
        "오전 방문");
    jdbc.update(
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['다른사용자'])",
        USER_B,
        PLACE_B,
        "노출 금지");

    List<PlaceSearchRow> anonymous = search(null, null, null, Optional.empty());
    List<PlaceSearchRow> savedOnly =
        repository.search(
            PlacesListQuery.of(null, null, null, null, null, null, null, 20, true),
            null,
            Optional.of(USER_A));

    assertThat(anonymous)
        .allSatisfy(
            row -> {
              assertThat(row.saved()).isFalse();
              assertThat(row.memo()).isNull();
              assertThat(row.tags()).isEmpty();
            });
    assertThat(savedOnly)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.placeId()).isEqualTo(PLACE_A);
              assertThat(row.memo()).isEqualTo("오전 방문");
              assertThat(row.tags()).containsExactly("동쪽", "필수");
            });
  }

  @Test
  void PostGIS_radius는_spheroid_epsilon_안쪽을_포함하고_바깥쪽을_제외하며_GiST_plan을_사용한다() {
    UUID tieA = UUID.fromString("32000000-0000-0000-0000-000000000019");
    UUID tieB = UUID.fromString("32000000-0000-0000-0000-000000000020");
    UUID boundary = UUID.fromString("32000000-0000-0000-0000-000000000021");
    UUID outside = UUID.fromString("32000000-0000-0000-0000-000000000022");
    insertProjectedPlace(tieB, "동일거리", 500.0);
    insertProjectedPlace(tieA, "동일거리", 500.0);
    // ST_Project/ST_DWithin spheroid calculations can differ by sub-millimeter rounding. Verify
    // the inclusive predicate with a stable pair immediately inside/outside the 1,000 m edge.
    insertProjectedPlace(boundary, "경계", 999.999);
    insertProjectedPlace(outside, "초과", 1_000.1);
    PlacesListQuery nearby =
        PlacesListQuery.of(null, "geo_test", "seongsan", 33.5, 126.5, 1_000, null, 20, false);

    List<PlaceSearchRow> rows = repository.search(nearby, null, Optional.empty());
    assertThat(rows).extracting(PlaceSearchRow::placeId).containsExactly(tieA, tieB, boundary);
    assertThat(
            repository.search(
                nearby, new PlaceSearchPosition(500L, "동일거리", tieA), Optional.empty()))
        .extracting(PlaceSearchRow::placeId)
        .containsExactly(tieB, boundary);

    jdbc.execute("set local enable_seqscan=off");
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                "explain select id from public.tour_places where ST_DWithin(location, ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,1000)",
                String.class));
    assertThat(plan).contains("idx_tour_places_location");
  }

  @Test
  void 동일이름_keyset은_tie_placeId로_경계삽입에도_중복없이_진행한다() {
    PlacesListQuery firstQuery =
        PlacesListQuery.of(
            null, "tourist_attraction", "seongsan", null, null, null, null, 1, false);
    List<PlaceSearchRow> first = repository.search(firstQuery, null, Optional.empty());
    PlaceSearchRow item = first.getFirst();
    UUID insertedBefore = UUID.fromString("32000000-0000-0000-0000-000000000010");
    insertPlace(
        insertedBefore,
        "content-new",
        "성산일출봉",
        "성산일출봉",
        "tourist_attraction",
        "seongsan",
        126.49,
        33.5,
        false,
        null);

    List<PlaceSearchRow> second =
        repository.search(
            firstQuery,
            new PlaceSearchPosition(null, item.normalizedName(), item.placeId()),
            Optional.empty());

    assertThat(second)
        .extracting(PlaceSearchRow::placeId)
        .doesNotContain(item.placeId(), insertedBefore);
  }

  private List<PlaceSearchRow> search(
      String query, String category, String region, Optional<UUID> user) {
    return repository.search(
        PlacesListQuery.of(query, category, region, null, null, null, null, 20, false), null, user);
  }

  private void insertUser(UUID userId) {
    jdbc.update("insert into auth.users(id,email) values (?,?)", userId, userId + "@example.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)",
        userId,
        userId + "@example.test");
  }

  private void insertPlace(
      UUID id,
      String contentId,
      String name,
      String normalizedName,
      String category,
      String region,
      double lng,
      double lat,
      boolean stale,
      Instant sourceDeletedAt) {
    jdbc.update(
        """
        insert into public.tour_places(
          id,content_id,name,normalized_name,category,region_code,region_label,address,location,
          source_provider,source_service,source_modified_at,stale,source_deleted_at)
        values (?,?,?,?,?,?,?,'제주',ST_SetSRID(ST_MakePoint(?,?),4326)::geography,
                'fixture','places-test',?, ?, ?)
        """,
        id,
        contentId,
        name,
        normalizedName,
        category,
        region,
        region,
        lng,
        lat,
        Timestamp.from(Instant.parse("2026-08-20T00:00:00Z")),
        stale,
        sourceDeletedAt == null ? null : Timestamp.from(sourceDeletedAt));
  }

  private void insertProjectedPlace(UUID id, String normalizedName, double meters) {
    jdbc.update(
        """
        insert into public.tour_places(
          id,content_id,name,normalized_name,category,region_code,location,source_provider,source_service)
        values (?, ?, ?, ?, 'geo_test','seongsan',
          ST_Project(ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,?,radians(90)),
          'fixture','places-test')
        """,
        id,
        "content-" + id,
        normalizedName,
        normalizedName,
        meters);
  }

  private void insertImage(
      UUID id, UUID placeId, int displayOrder, String imageUrl, Instant tombstonedAt) {
    jdbc.update(
        """
        insert into public.place_images(
          id,place_id,image_url,display_order,source_provider,source_service,tombstoned_at)
        values (?,?,?,?,'admin_upload','places-test',?)
        """,
        id,
        placeId,
        imageUrl,
        displayOrder,
        tombstonedAt == null ? null : Timestamp.from(tombstonedAt));
  }
}
