package com.timingjeju.api.global.demo;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.context.annotation.Profile("local")
public class JdbcDemoStorageReader implements DemoStorageReader {
  private static final int ROW_LIMIT = 40;
  private static final String TOUR_PLACES_SQL =
      """
      select id, import_run_id, content_id, content_type_id, name,
             category, address, overview, image_url, thumbnail_url,
             ST_X(location::geometry), ST_Y(location::geometry)
        from tour_places
       order by updated_at desc
       limit ?
      """;
  private static final String DETAIL_INFO_SWEEP_STATS_SQL =
      """
      select coalesce(sum(expected_total), 0)::bigint as expected_total,
             coalesce(sum(page_count), 0)::bigint as page_count
        from tour_api_detail_item_sweeps
       where import_run_id = ?
      """;
  private static final String DETAIL_IMAGE_SWEEP_STATS_SQL =
      """
      select coalesce(sum(expected_total), 0)::bigint as expected_total,
             coalesce(sum(page_count), 0)::bigint as page_count
        from tour_api_place_image_sweeps
       where import_run_id = ?
      """;
  private static final String RUN_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from data_import_runs
      """;
  private static final String SNAPSHOT_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from external_api_snapshots
      """;
  private static final String PLACE_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from tour_places
      """;
  private static final String PLACE_DETAIL_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from place_details
      """;
  private static final String PLACE_DETAIL_ITEM_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from place_detail_items
      """;
  private static final String PLACE_IMAGE_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from place_images
      """;
  private static final String PROVENANCE_COUNT_SQL =
      """
      select coalesce(count(*), 0)
        from tour_api_operation_provenance
      """;

  private final JdbcTemplate jdbc;

  public JdbcDemoStorageReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public DemoStorageView latest() {
    List<DemoRunRow> runs =
        jdbc.query(
            """
            select id, source_kind, source_operation, status, fetched_count, inserted_count, started_at
              from data_import_runs
             order by started_at desc
             limit 20
            """,
            (rs, row) ->
                new DemoRunRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("source_kind"),
                    rs.getString("source_operation"),
                    rs.getString("status"),
                    rs.getInt("fetched_count"),
                    rs.getInt("inserted_count"),
                    rs.getTimestamp("started_at").toInstant()));

    List<DemoSnapshotRow> snapshots =
        jdbc.query(
            """
            select id, import_run_id, source_operation, parse_status, payload_size_bytes
              from external_api_snapshots
             order by fetched_at desc
             limit 80
            """,
            (rs, row) ->
                new DemoSnapshotRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("import_run_id", UUID.class),
                    rs.getString("source_operation"),
                    rs.getString("parse_status"),
                    rs.getLong("payload_size_bytes")));

    List<DemoPlaceRow> places =
        jdbc.query(
            TOUR_PLACES_SQL,
            ps -> ps.setInt(1, ROW_LIMIT),
            (rs, row) ->
                new DemoPlaceRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("import_run_id", UUID.class),
                    rs.getString("content_id"),
                    rs.getString("content_type_id"),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getString("address"),
                    rs.getString("overview"),
                    rs.getString("image_url"),
                    rs.getString("thumbnail_url"),
                    rs.getObject("st_x", Double.class),
                    rs.getObject("st_y", Double.class)));

    List<DemoPlaceDetailRow> placeDetails =
        jdbc.query(
            """
            select place_id, import_run_id, phone, operating_hours_text,
                   closed_days_text, parking_text, intro_attributes, source_snapshot_id
              from place_details
              join (select id from tour_places order by updated_at desc limit ?) p on p.id = place_id
             order by place_id
            """,
            ps -> ps.setInt(1, ROW_LIMIT),
            (rs, row) ->
                new DemoPlaceDetailRow(
                    rs.getObject("place_id", UUID.class),
                    rs.getObject("import_run_id", UUID.class),
                    rs.getString("phone"),
                    rs.getString("operating_hours_text"),
                    rs.getString("closed_days_text"),
                    rs.getString("parking_text"),
                    rs.getString("intro_attributes"),
                    rs.getObject("source_snapshot_id", UUID.class)));

    List<DemoPlaceImageRow> placeImages =
        jdbc.query(
            """
            select i.id, i.place_id, i.image_url, i.thumbnail_url, i.import_run_id, i.source_image_id
              from place_images i
              join (select id from tour_places order by updated_at desc limit ?) p on p.id = i.place_id
             order by i.place_id, i.display_order
            """,
            ps -> ps.setInt(1, ROW_LIMIT),
            (rs, row) ->
                new DemoPlaceImageRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("place_id", UUID.class),
                    rs.getString("image_url"),
                    rs.getString("thumbnail_url"),
                    rs.getObject("import_run_id", UUID.class),
                    rs.getString("source_image_id")));

    List<DemoPlaceDetailItemRow> detailItems =
        jdbc.query(
            """
            select i.id, i.place_id, i.content_type_id, i.item_type, i.source_item_key,
                   i.sequence_no, i.title, i.import_run_id
              from place_detail_items i
              join (select id from tour_places order by updated_at desc limit ?) p on p.id = i.place_id
             order by place_id, sequence_no
            """,
            ps -> ps.setInt(1, ROW_LIMIT),
            (rs, row) ->
                new DemoPlaceDetailItemRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("place_id", UUID.class),
                    rs.getString("content_type_id"),
                    rs.getString("item_type"),
                    rs.getString("source_item_key"),
                    rs.getInt("sequence_no"),
                    rs.getString("title"),
                    rs.getObject("import_run_id", UUID.class)));

    List<UUID> provenanceRowIds = new ArrayList<>();
    provenanceRowIds.addAll(places.stream().map(DemoPlaceRow::id).toList());
    provenanceRowIds.addAll(placeDetails.stream().map(DemoPlaceDetailRow::placeId).toList());
    provenanceRowIds.addAll(detailItems.stream().map(DemoPlaceDetailItemRow::id).toList());
    provenanceRowIds.addAll(placeImages.stream().map(DemoPlaceImageRow::id).toList());
    List<DemoProvenanceRow> provenances = queryProvenance(provenanceRowIds);

    return new DemoStorageView(
        runs,
        snapshots,
        places,
        placeDetails,
        detailItems,
        placeImages,
        provenances,
        count(RUN_COUNT_SQL),
        count(SNAPSHOT_COUNT_SQL),
        count(PLACE_COUNT_SQL),
        count(PLACE_DETAIL_COUNT_SQL),
        count(PLACE_DETAIL_ITEM_COUNT_SQL),
        count(PLACE_IMAGE_COUNT_SQL),
        count(PROVENANCE_COUNT_SQL));
  }

  @Override
  public List<DemoPlaceRow> candidates(UUID listRunId, String... contentTypeIds) {
    if (contentTypeIds.length == 0) {
      return List.of();
    }
    String placeholders = String.join(",", Collections.nCopies(contentTypeIds.length, "?"));
    String sql =
        "with ranked as ("
            + "\n"
            + "  select t.id, t.import_run_id, t.content_id, t.content_type_id, t.name,"
            + "\n"
            + "         t.category, t.address,"
            + "\n"
            + "         t.overview, t.image_url, t.thumbnail_url,"
            + "\n"
            + "         st_x(t.location::geometry) as longitude,"
            + "\n"
            + "         st_y(t.location::geometry) as latitude,"
            + "\n"
            + "         row_number() over ("
            + "\n"
            + "           partition by t.content_type_id order by p.created_at desc"
            + "\n"
            + "         ) as rn"
            + "\n"
            + "    from tour_places t"
            + "\n"
            + "    join tour_api_operation_provenance p"
            + "\n"
            + "      on p.normalized_entity_type = 'tour_places'"
            + "\n"
            + "     and p.normalized_row_id = t.id"
            + "\n"
            + "     and p.operation_key = 'areaBasedList2'"
            + "\n"
            + "     and p.import_run_id = ?"
            + "\n"
            + "   where p.content_type_id in ("
            + placeholders
            + ")\n"
            + ")"
            + "\n"
            + "select id, import_run_id, content_id, content_type_id, name,"
            + "\n"
            + "       category, address, overview, image_url, thumbnail_url, longitude, latitude"
            + "\n"
            + "  from ranked"
            + "\n"
            + " where rn = 1"
            + "\n"
            + " order by content_type_id";
    Object[] params = new Object[contentTypeIds.length + 1];
    params[0] = listRunId;
    System.arraycopy(contentTypeIds, 0, params, 1, contentTypeIds.length);
    return jdbc.query(
        sql,
        params,
        (rs, row) ->
            new DemoPlaceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("import_run_id", UUID.class),
                rs.getString("content_id"),
                rs.getString("content_type_id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("address"),
                rs.getString("overview"),
                rs.getString("image_url"),
                rs.getString("thumbnail_url"),
                rs.getObject("longitude", Double.class),
                rs.getObject("latitude", Double.class)));
  }

  private List<DemoProvenanceRow> queryProvenance(List<UUID> rowIds) {
    if (rowIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", Collections.nCopies(rowIds.size(), "?"));
    String sql =
        "select id, normalized_entity_type, normalized_row_id, operation_key,"
            + "\n"
            + "       content_type_id, request_fingerprint, source_snapshot_id, import_run_id"
            + "\n"
            + "  from tour_api_operation_provenance"
            + "\n"
            + " where normalized_row_id in ("
            + placeholders
            + ")"
            + "\n"
            + " order by normalized_row_id, created_at";
    return jdbc.query(
        sql,
        rowIds.toArray(),
        (rs, row) ->
            new DemoProvenanceRow(
                rs.getObject("id", UUID.class),
                rs.getString("normalized_entity_type"),
                rs.getObject("normalized_row_id", UUID.class),
                rs.getString("operation_key"),
                rs.getString("content_type_id"),
                rs.getString("request_fingerprint"),
                rs.getObject("source_snapshot_id", UUID.class),
                rs.getObject("import_run_id", UUID.class)));
  }

  @Override
  public DemoSweepStats sweepStats(UUID importRunId, String operation) {
    return switch (operation) {
      case "detailInfo2" -> querySweepStats(importRunId, DETAIL_INFO_SWEEP_STATS_SQL);
      case "detailImage2" -> querySweepStats(importRunId, DETAIL_IMAGE_SWEEP_STATS_SQL);
      default -> DemoSweepStats.empty();
    };
  }

  private DemoSweepStats querySweepStats(UUID importRunId, String sql) {
    return jdbc.query(
            sql,
            (rs, row) -> new DemoSweepStats(rs.getInt("expected_total"), rs.getInt("page_count")),
            importRunId)
        .getFirst();
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }
}
