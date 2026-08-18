package com.timingjeju.api.global.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.TimingJejuApiApplication;
import com.timingjeju.api.application.demo.DemoPlaceDetailItemRow;
import com.timingjeju.api.application.demo.DemoPlaceDetailRow;
import com.timingjeju.api.application.demo.DemoPlaceImageRow;
import com.timingjeju.api.application.demo.DemoPlaceRow;
import com.timingjeju.api.application.demo.DemoProvenanceRow;
import com.timingjeju.api.application.demo.DemoRunRow;
import com.timingjeju.api.application.demo.DemoSnapshotRow;
import com.timingjeju.api.application.demo.DemoStorageReader;
import com.timingjeju.api.application.demo.DemoStorageView;
import com.timingjeju.api.application.demo.DemoSweepStats;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest(classes = TimingJejuApiApplication.class)
@Import({
  PostgreSqlTestcontainersConfiguration.class,
  JdbcDemoStorageReaderIntegrationTest.DemoStorageReaderTestConfiguration.class
})
@ActiveProfiles("postgresql-integration")
class JdbcDemoStorageReaderIntegrationTest {
  private static final UUID LIST_RUN = UUID.fromString("36000000-0000-0000-0000-000000000001");
  private static final UUID LIST_SNAPSHOT = UUID.fromString("36000000-0000-0000-0000-000000000002");
  private static final UUID DETAILS_RUN = UUID.fromString("36000000-0000-0000-0000-000000000003");
  private static final UUID DETAILS_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000004");
  private static final UUID DETAILS_INTRO_RUN =
      UUID.fromString("36000000-0000-0000-0000-000000000009");
  private static final UUID DETAILS_INTRO_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000010");
  private static final UUID ITEMS_RUN = UUID.fromString("36000000-0000-0000-0000-000000000005");
  private static final UUID IMAGES_RUN = UUID.fromString("36000000-0000-0000-0000-000000000006");
  private static final UUID ITEMS_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000007");
  private static final UUID IMAGES_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000008");
  private static final UUID PLACE_ID = UUID.fromString("36000000-0000-0000-0000-000000000101");
  private static final UUID PLACE_DETAIL_ID = PLACE_ID;
  private static final UUID DETAIL_ITEM_ID =
      UUID.fromString("36000000-0000-0000-0000-000000000102");
  private static final UUID PLACE_IMAGE_ID =
      UUID.fromString("36000000-0000-0000-0000-000000000103");
  private static final UUID PLACE_IGNORED_ID =
      UUID.fromString("36000000-0000-0000-0000-000000000201");
  private static final UUID LIST_IGNORED_RUN =
      UUID.fromString("36000000-0000-0000-0000-000000000202");
  private static final UUID LIST_IGNORED_SNAPSHOT =
      UUID.fromString("36000000-0000-0000-0000-000000000203");
  private static final UUID INFO_SWEEP = UUID.fromString("36000000-0000-0000-0000-000000000301");
  private static final UUID IMAGE_SWEEP = UUID.fromString("36000000-0000-0000-0000-000000000302");
  private static final Instant NOW = Instant.parse("2026-08-18T01:00:00Z");
  private static final String HASH_64 =
      "3030303030303030303030303030303030303030303030303030303030303030";
  private static final String AREA_FINGERPRINT_1 =
      "6130303030303030303030303030303030303030303030303030303030303030";
  private static final String AREA_FINGERPRINT_2 =
      "6230303030303030303030303030303030303030303030303030303030303030";
  private static final String DETAIL_COMMON_FINGERPRINT =
      "6330303030303030303030303030303030303030303030303030303030303030";
  private static final String DETAIL_INTRO_FINGERPRINT =
      "6430303030303030303030303030303030303030303030303030303030303030";
  private static final String DETAIL_INFO_FINGERPRINT =
      "6530303030303030303030303030303030303030303030303030303030303030";
  private static final String DETAIL_IMAGE_FINGERPRINT =
      "6630303030303030303030303030303030303030303030303030303030303030";

  @Autowired private DemoStorageReader reader;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    insertRunAndSnapshot(
        LIST_RUN, "areaBasedList2", AREA_FINGERPRINT_1, LIST_SNAPSHOT, "l1", "content:test");
    insertRunAndSnapshot(
        DETAILS_RUN,
        "detailCommon2",
        DETAIL_COMMON_FINGERPRINT,
        DETAILS_SNAPSHOT,
        "l2",
        "content:test");
    insertRunAndSnapshot(
        DETAILS_INTRO_RUN,
        "detailIntro2",
        DETAIL_INTRO_FINGERPRINT,
        DETAILS_INTRO_SNAPSHOT,
        "l2",
        "content:test");
    insertRunAndSnapshot(
        ITEMS_RUN, "detailInfo2", DETAIL_INFO_FINGERPRINT, ITEMS_SNAPSHOT, "l3", "content:test");
    insertRunAndSnapshot(
        IMAGES_RUN,
        "detailImage2",
        DETAIL_IMAGE_FINGERPRINT,
        IMAGES_SNAPSHOT,
        "l4",
        "content:test");
    insertRunAndSnapshot(
        LIST_IGNORED_RUN,
        "areaBasedList2",
        AREA_FINGERPRINT_2,
        LIST_IGNORED_SNAPSHOT,
        "l5",
        "content:ignored");

    insertTourApiOperations("detailInfo2", "detailImage2");

    insertPlace(PLACE_ID, "100", "200", LIST_RUN, LIST_SNAPSHOT, "12", "성산일출봉");
    insertPlace(
        PLACE_IGNORED_ID, "101", "201", LIST_IGNORED_RUN, LIST_IGNORED_SNAPSHOT, "12", "무인도");
    insertPlaceDetail(PLACE_ID, DETAILS_RUN, DETAILS_SNAPSHOT);
    insertPlaceDetailItem(PLACE_ID, ITEMS_RUN, ITEMS_SNAPSHOT, DETAIL_ITEM_ID);
    insertPlaceImage(PLACE_ID, IMAGES_RUN, IMAGES_SNAPSHOT, PLACE_IMAGE_ID);
    insertProvenance(
        "tour_places",
        PLACE_ID,
        "areaBasedList2",
        "12",
        LIST_RUN,
        LIST_SNAPSHOT,
        AREA_FINGERPRINT_1);
    insertProvenance(
        "tour_places",
        PLACE_IGNORED_ID,
        "areaBasedList2",
        "12",
        LIST_IGNORED_RUN,
        LIST_IGNORED_SNAPSHOT,
        AREA_FINGERPRINT_2);
    insertProvenance(
        "place_details",
        PLACE_ID,
        "detailCommon2",
        "12",
        DETAILS_RUN,
        DETAILS_SNAPSHOT,
        DETAIL_COMMON_FINGERPRINT);
    insertProvenance(
        "place_details",
        PLACE_ID,
        "detailIntro2",
        "12",
        DETAILS_INTRO_RUN,
        DETAILS_INTRO_SNAPSHOT,
        DETAIL_INTRO_FINGERPRINT);
    insertProvenance(
        "place_detail_items",
        DETAIL_ITEM_ID,
        "detailInfo2",
        "12",
        ITEMS_RUN,
        ITEMS_SNAPSHOT,
        DETAIL_INFO_FINGERPRINT);
    insertProvenance(
        "place_images",
        PLACE_IMAGE_ID,
        "detailImage2",
        "12",
        IMAGES_RUN,
        IMAGES_SNAPSHOT,
        DETAIL_IMAGE_FINGERPRINT);
    insertSweep("tour_api_detail_item_sweeps", ITEMS_RUN, PLACE_ID, 21, 3);
    insertSweep("tour_api_place_image_sweeps", IMAGES_RUN, PLACE_ID, 13, 2);
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  @Test
  void latest_조회는_모든_테이블_행과_요약을_반환한다() {
    DemoStorageView view = reader.latest();

    assertThat(view.runs().stream().map(DemoRunRow::id))
        .contains(LIST_RUN, DETAILS_RUN, ITEMS_RUN, IMAGES_RUN, LIST_IGNORED_RUN);
    assertThat(view.snapshots().stream().map(DemoSnapshotRow::id))
        .contains(
            LIST_SNAPSHOT,
            DETAILS_SNAPSHOT,
            ITEMS_SNAPSHOT,
            IMAGES_SNAPSHOT,
            LIST_IGNORED_SNAPSHOT);
    assertThat(view.places())
        .extracting(DemoPlaceRow::id)
        .containsExactlyInAnyOrder(PLACE_ID, PLACE_IGNORED_ID);
    assertThat(view.placeDetails())
        .extracting(DemoPlaceDetailRow::placeId)
        .containsExactly(PLACE_ID);
    assertThat(view.detailItems())
        .extracting(DemoPlaceDetailItemRow::id)
        .containsExactly(DETAIL_ITEM_ID);
    assertThat(view.placeImages())
        .extracting(DemoPlaceImageRow::id)
        .containsExactly(PLACE_IMAGE_ID);
    assertThat(view.provenances())
        .extracting(DemoProvenanceRow::normalizedRowId)
        .contains(PLACE_ID);
    assertThat(view.totalRuns()).isEqualTo(6);
    assertThat(view.totalSnapshots()).isEqualTo(6);
    assertThat(view.totalPlaces()).isEqualTo(2);
    assertThat(view.totalPlaceDetails()).isEqualTo(1);
    assertThat(view.totalDetailItems()).isEqualTo(1);
    assertThat(view.totalPlaceImages()).isEqualTo(1);
    assertThat(view.totalProvenances()).isEqualTo(6);
  }

  @Test
  void latest_조회는_샘플_최대치와_총건수_차이를_보여준다() {
    for (int index = 0; index < 45; index++) {
      insertPlace(
          UUID.nameUUIDFromBytes(("overflow-" + index).getBytes()),
          String.valueOf(200 + index),
          String.valueOf(300 + index),
          LIST_RUN,
          LIST_SNAPSHOT,
          "12",
          "추가-" + index);
    }

    DemoStorageView view = reader.latest();

    assertThat(view.places()).hasSize(40);
    assertThat(view.totalPlaces()).isEqualTo(47);
  }

  @Test
  void candidates는_리스트_런_ID로_프로베넌스_스코프를_제한한다() {
    assertThat(reader.candidates(LIST_RUN, "12", "32", "39"))
        .extracting(DemoPlaceRow::id)
        .containsExactly(PLACE_ID);
    assertThat(reader.candidates(LIST_IGNORED_RUN, "12", "32", "39"))
        .extracting(DemoPlaceRow::id)
        .containsExactly(PLACE_IGNORED_ID);
  }

  @Test
  void sweep_stats는_요청_operation별_expected_page를_반영한다() {
    DemoSweepStats itemStats = reader.sweepStats(ITEMS_RUN, "detailInfo2");
    DemoSweepStats imageStats = reader.sweepStats(IMAGES_RUN, "detailImage2");
    DemoSweepStats unknown = reader.sweepStats(IMAGES_RUN, "detailSomethingElse");

    assertThat(itemStats).isEqualTo(new DemoSweepStats(21, 3));
    assertThat(imageStats).isEqualTo(new DemoSweepStats(13, 2));
    assertThat(unknown).isEqualTo(DemoSweepStats.empty());
  }

  private void insertRunAndSnapshot(
      UUID runId,
      String operation,
      String fingerprint,
      UUID snapshotId,
      String pageNo,
      String scopeKey) {
    jdbc.update(
        """
        insert into public.data_import_runs (
          id, source_kind, source_name, source_operation, data_version, status, started_at,
          parser_version, schema_version, sync_mode, scope_key, request_fingerprint, idempotency_key,
          source_provider, source_service
        ) values (?, 'tour_api', 'fixture', ?, 'v1', 'running', ?, 'parser-v1', 'schema-v1',
          'incremental', ?, ?, ?, 'tour-api', 'KorService2')
        """,
        runId,
        operation,
        Timestamp.from(NOW),
        scopeKey,
        fingerprint,
        "issue-148-" + runId);
    jdbc.update(
        """
        insert into public.external_api_snapshots (
          id, import_run_id, source_provider, source_service, source_operation, scope_key,
          request_hash, page_key, fetched_at, parser_version, payload_hash, request_metadata_redacted,
          raw_payload, payload_size_bytes, redaction_version, payload_format, initial_parse_status,
          parse_status, parsed_at
        ) values (?, ?, 'tour-api', 'KorService2', ?, ?, ?, ?, ?, 'v1', ?, '{}'::jsonb, '{}'::jsonb, 2,
          'test-v1', 'JSON', 'parsed', 'parsed', ?)
        """,
        snapshotId,
        runId,
        operation,
        scopeKey,
        fingerprint,
        pageNo,
        Timestamp.from(NOW),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        Timestamp.from(NOW));
  }

  private void insertPlace(
      UUID placeId,
      String externalPlaceId,
      String contentId,
      UUID runId,
      UUID snapshotId,
      String contentTypeId,
      String name) {
    jdbc.update(
        """
        insert into public.tour_places (
          id, external_place_id, content_id, content_type_id, name, normalized_name, category, address,
          location, source_provider, source_service, source_modified_at, import_run_id, source_snapshot_id,
          last_seen_at, address_detail, image_url, thumbnail_url
        ) values (?, ?, ?, ?, ?, ?, 'AT', '제주',
          ST_SetSRID(ST_MakePoint(126.5, 33.5), 4326)::geography, 'tour-api', 'KorService2',
          ?, ?, ?, ?, ?, ?, ?)
        """,
        placeId,
        externalPlaceId,
        contentId,
        contentTypeId,
        name,
        name,
        Timestamp.from(NOW.minusSeconds(120)),
        runId,
        snapshotId,
        Timestamp.from(NOW),
        "서귀포시",
        "https://example.test/photo.jpg",
        "https://example.test/photo-small.jpg");
  }

  private void insertPlaceDetail(UUID placeId, UUID runId, UUID snapshotId) {
    jdbc.update(
        """
        insert into public.place_details (
          place_id, phone, homepage_url, operating_hours_text, closed_days_text, parking_text,
          intro_attributes, source_provider, source_service, source_snapshot_id, import_run_id,
          fetched_at, last_seen_at, updated_at, source_updated_at
        ) values (?, '064-0000', ?, '09:00', '연중무휴', '무료', '{"intro":"ok"}'::jsonb,
          'tour-api', 'KorService2', ?, ?, ?, ?, ?, null::timestamptz)
        """,
        placeId,
        "https://example.test/home",
        snapshotId,
        runId,
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void insertPlaceDetailItem(UUID placeId, UUID runId, UUID snapshotId, UUID itemId) {
    jdbc.update(
        """
        insert into public.place_detail_items (
          id, place_id, source_provider, source_service, content_type_id, item_type,
          source_item_key, title, sequence_no, payload_hash, source_snapshot_id, import_run_id,
          last_seen_at, created_at, updated_at, attributes
        ) values (?, ?, 'tour-api', 'KorService2', '12', 'overview', 'overview-1',
          '상세', 1, 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', ?, ?, ?, ?, ?, ?::jsonb)
        """,
        itemId,
        placeId,
        snapshotId,
        runId,
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        "{\"title\":\"상세\"}");
  }

  private void insertPlaceImage(UUID placeId, UUID runId, UUID snapshotId, UUID imageId) {
    jdbc.update(
        """
        insert into public.place_images (
          id, place_id, image_url, source_provider, source_service, source_snapshot_id, source_image_id,
          payload_hash, source_sweep_id, import_run_id, last_seen_at, created_at,
          display_order, image_name
        ) values (?, ?, 'https://example.test/photo.jpg', 'tour-api', 'KorService2', ?, 'img-1',
          ?, ?, ?, ?, now(), 1, 'photo')
        """,
        imageId,
        placeId,
        snapshotId,
        HASH_64,
        null,
        runId,
        Timestamp.from(NOW));
  }

  private void insertProvenance(
      String entityType,
      UUID normalizedRowId,
      String operation,
      String contentTypeId,
      UUID importRunId,
      UUID snapshotId,
      String fingerprint) {
    jdbc.update(
        """
        insert into public.tour_api_operation_provenance (
          normalized_entity_type, normalized_row_id, operation_key, content_type_id,
          request_fingerprint, source_snapshot_id, import_run_id
        ) values (?, ?, ?, ?, ?, ?, ?)
        """,
        entityType,
        normalizedRowId,
        operation,
        contentTypeId,
        fingerprint,
        snapshotId,
        importRunId);
  }

  private void insertTourApiOperations(String... operations) {
    for (String operation : operations) {
      jdbc.update(
          "insert into public.tour_api_operations (operation_key) values (?) on conflict do nothing",
          operation);
    }
  }

  private void insertSweep(
      String table, UUID importRunId, UUID placeId, int expectedTotal, int pageCount) {
    jdbc.update(
        """
        insert into public.%s (
          id, place_id, source_provider, source_service, content_id, content_type_id,
          import_run_id, manifest_hash, fetched_at, expected_total, page_count, accepted_at
        ) values (?, ?, 'tour-api', 'KorService2', '100', '12', ?, ?, ?, ?, ?, ?)
        """
            .formatted(table),
        table.equals("tour_api_detail_item_sweeps") ? INFO_SWEEP : IMAGE_SWEEP,
        placeId,
        importRunId,
        HASH_64,
        Timestamp.from(NOW),
        expectedTotal,
        pageCount,
        Timestamp.from(NOW));
  }

  private void clean() {
    jdbc.update("delete from public.tour_api_operation_provenance");
    jdbc.update("delete from public.tour_api_place_image_sweeps");
    jdbc.update("delete from public.tour_api_detail_item_sweeps");
    jdbc.update("delete from public.place_images");
    jdbc.update("delete from public.place_detail_items");
    jdbc.update("delete from public.place_details");
    jdbc.update("delete from public.tour_places");
    jdbc.update("delete from public.external_api_snapshots");
    jdbc.update("delete from public.data_import_runs");
  }

  @Configuration(proxyBeanMethods = false)
  static class DemoStorageReaderTestConfiguration {
    @Bean
    DemoStorageReader reader(JdbcTemplate jdbcTemplate) {
      return new JdbcDemoStorageReader(jdbcTemplate);
    }
  }
}
