package com.timingjeju.api.domain.places.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.places.dto.response.PlaceDetailResponse;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import com.timingjeju.api.domain.places.service.PlaceDetailService;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcPublicPlaceDetailRepositoryIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID PLACE = UUID.fromString("33000000-0000-0000-0000-000000000001");
  private static final UUID NO_DETAIL = UUID.fromString("33000000-0000-0000-0000-000000000002");
  private static final UUID DELETED = UUID.fromString("33000000-0000-0000-0000-000000000003");
  private static final UUID USER_A = UUID.fromString("33000000-0000-0000-0000-000000000011");
  private static final UUID USER_B = UUID.fromString("33000000-0000-0000-0000-000000000012");

  @Autowired private JdbcPublicPlaceDetailRepository repository;
  @Autowired private PlaceDetailService service;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    insertUser(USER_A);
    insertUser(USER_B);
    insertPlace(PLACE, "detail-active", null);
    insertPlace(NO_DETAIL, "detail-empty", null);
    insertPlace(DELETED, "detail-deleted", Instant.parse("2026-08-21T00:00:00Z"));
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
        "insert into public.saved_places(user_id,place_id,memo,tags) values (?,?,?,array['필수','동쪽'])",
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
  void 상세_query는_place_PK와_image_order_index_plan을_사용한다() {
    jdbc.execute("set local enable_seqscan=off");
    String plan =
        String.join(
            "\n",
            jdbc.queryForList(
                """
                explain select p.id
                from public.tour_places p
                left join public.place_images image
                  on image.place_id=p.id and image.tombstoned_at is null
                where p.id='33000000-0000-0000-0000-000000000001'::uuid
                  and p.source_deleted_at is null
                order by image.display_order asc nulls last, image.id asc nulls last
                """,
                String.class));

    assertThat(plan)
        .contains("tour_places_pkey")
        .contains("Sort Key: image.display_order, image.id")
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
}
