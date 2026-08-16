package com.timingjeju.api.global.tourapi.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncException;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiIncrementalSyncParserTest {

  private final TourApiIncrementalSyncParser parser =
      new TourApiIncrementalSyncParser(new ObjectMapper());

  @Test
  void active와_delete_marker를_source_modified_time과_함께_구분한다() {
    var page = parser.parse(SnapshotPayloadFormat.JSON, response(active("100"), deleted("200")));

    assertThat(page.pageNo()).isOne();
    assertThat(page.numOfRows()).isEqualTo(100);
    assertThat(page.totalCount()).isEqualTo(2);
    assertThat(page.rawItemCount()).isEqualTo(2);
    assertThat(page.changes())
        .extracting(change -> change.action())
        .containsExactly(PlaceSyncAction.UPSERT, PlaceSyncAction.DELETE);
    assertThat(page.changes())
        .extracting(change -> change.contentId())
        .containsExactly("100", "200");
    assertThat(page.changes())
        .extracting(change -> change.sourceModifiedAt())
        .containsExactly(
            Instant.parse("2026-08-16T01:02:03Z"), Instant.parse("2026-08-16T01:02:04Z"));
    assertThat(page.changes().getFirst().place()).isNotNull();
    assertThat(page.changes().getLast().place()).isNull();
  }

  @Test
  void duplicate_contentid와_unknown_showflag는_page_전체를_거부한다() {
    assertThatThrownBy(
            () -> parser.parse(SnapshotPayloadFormat.JSON, response(active("100"), active("100"))))
        .isInstanceOf(IncrementalSyncException.class);
    assertThatThrownBy(
            () -> parser.parse(SnapshotPayloadFormat.JSON, response(withShowFlag("100", "X"))))
        .isInstanceOf(IncrementalSyncException.class);
  }

  @Test
  void source_modified_time이_없거나_잘못되면_거부한다() {
    assertThatThrownBy(
            () -> parser.parse(SnapshotPayloadFormat.JSON, response(withModified("100", ""))))
        .isInstanceOf(IncrementalSyncException.class);
    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON, response(withModified("100", "20261399010203"))))
        .isInstanceOf(IncrementalSyncException.class);
  }

  @Test
  void totalCount_0의_정상_빈_응답은_empty_page이고_items_누락은_거부한다() {
    byte[] empty =
        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"pageNo\":1,\"numOfRows\":100,\"totalCount\":0,\"items\":\"\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] missing =
        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"pageNo\":1,\"numOfRows\":100,\"totalCount\":0}}}"
            .getBytes(StandardCharsets.UTF_8);

    assertThat(parser.parse(SnapshotPayloadFormat.JSON, empty).changes()).isEmpty();
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, missing))
        .isInstanceOf(IncrementalSyncException.class);
  }

  private static byte[] response(String... items) {
    return ("""
        {"response":{"header":{"resultCode":"0000"},"body":{"pageNo":1,"numOfRows":100,
        "totalCount":%d,"items":{"item":[%s]}}}}
        """
            .formatted(items.length, String.join(",", items)))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String active(String contentId) {
    return """
        {"contentid":"%s","contenttypeid":"12","title":"성산일출봉",
         "mapx":"126.941516","mapy":"33.458111","lDongRegnCd":"50",
         "modifiedtime":"20260816100203","showflag":"1"}
        """
        .formatted(contentId);
  }

  private static String deleted(String contentId) {
    return """
        {"contentid":"%s","contenttypeid":"12",
         "modifiedtime":"20260816100204","showflag":"0"}
        """
        .formatted(contentId);
  }

  private static String withShowFlag(String contentId, String showFlag) {
    return active(contentId).replace("\"showflag\":\"1\"", "\"showflag\":\"" + showFlag + "\"");
  }

  private static String withModified(String contentId, String modified) {
    return active(contentId)
        .replace("\"modifiedtime\":\"20260816100203\"", "\"modifiedtime\":\"" + modified + "\"");
  }
}
