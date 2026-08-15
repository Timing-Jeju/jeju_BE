package com.timingjeju.api.global.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detailitem.DetailItemImportException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiDetailInfoParserTest {
  private final TourApiDetailInfoParser parser =
      new TourApiDetailInfoParser(new ObjectMapper(), new DetailItemContentSanitizer());

  @Test
  void 응답순서를_sequence로_고정하고_attributes_schema_version과_안전한_text를_보존한다() {
    var result =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                envelope(
                    "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"20\",\"infoname\":\"두 번째\",\"infotext\":\"<p>안내<script>evil()</script></p>\"},"
                        + "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"10\",\"infoname\":\"첫 번째\",\"infotext\":\"<b>본문</b>\"}")),
            "100",
            "12");

    assertThat(result.contentId()).isEqualTo("100");
    assertThat(result.contentTypeId()).isEqualTo("12");
    assertThat(result.items()).extracting("sourceItemKey").containsExactly("20", "10");
    assertThat(result.items()).extracting("sequenceNo").containsExactly(1, 2);
    assertThat(result.items().getFirst().attributes().schema())
        .isEqualTo("tour-api.detailInfo2.info");
    assertThat(result.items().getFirst().attributes().version()).isEqualTo(1);
    assertThat(result.items().getFirst().attributes().fields().get("infotext"))
        .isEqualTo("안내")
        .doesNotContain("script", "evil");
  }

  @Test
  void 숙박과_코스의_공급자_key를_안정적인_source_item_key로_사용하고_위험_URL은_버린다() {
    var room =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                envelope(
                    "{\"contentid\":\"200\",\"contenttypeid\":\"32\",\"roomcode\":\"R-1\",\"roomtitle\":\"한실\",\"roomintro\":\"<p>객실</p>\",\"roomimg1\":\"javascript:alert(1)\"}")),
            "200",
            "32");
    var course =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                envelope(
                    "{\"contentid\":\"300\",\"contenttypeid\":\"25\",\"subcontentid\":\"C-1\",\"subname\":\"1코스\",\"subnum\":\"2\",\"subdetailimg\":\"https://images.example.test/course.jpg\"}")),
            "300",
            "25");

    assertThat(room.items().getFirst().sourceItemKey()).isEqualTo("R-1");
    assertThat(room.items().getFirst().itemType()).isEqualTo("room");
    assertThat(room.items().getFirst().attributes().fields())
        .containsEntry("roomintro", "객실")
        .doesNotContainKey("roomimg1");
    assertThat(course.items().getFirst().sourceItemKey()).isEqualTo("C-1");
    assertThat(course.items().getFirst().itemType()).isEqualTo("course");
    assertThat(course.items().getFirst().attributes().fields())
        .containsEntry("subdetailimg", "https://images.example.test/course.jpg");
  }

  @Test
  void 빈_key와_동일_key_중복과_unknown_content_type을_거부한다() {
    assertInvalid(envelope("{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\" \"}"));
    assertInvalid(
        envelope(
            "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"1\"},"
                + "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"1\"}"));
    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        envelope(
                            "{\"contentid\":\"100\",\"contenttypeid\":\"99\",\"serialnum\":\"1\"}")),
                    "100",
                    "99"))
        .isInstanceOf(DetailItemImportException.class);
  }

  @Test
  void totalCount가_0인_정상_빈_응답은_empty_batch이고_items_구조_누락은_거부한다() {
    String empty =
        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\",\"totalCount\":0}}}";
    var result = parser.parse(SnapshotPayloadFormat.JSON, bytes(empty), "100", "12");

    assertThat(result.items()).isEmpty();
    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON,
                    bytes("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{}}}"),
                    "100",
                    "12"))
        .isInstanceOf(DetailItemImportException.class);
  }

  @Test
  void attribute의_비문자_타입과_64KiB_초과를_거부한다() {
    assertInvalid(
        envelope(
            "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infotext\":123}"));
    assertInvalid(
        envelope(
            "{\"contentid\":\"100\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infotext\":\""
                + "가".repeat(22_000)
                + "\"}"));
  }

  private void assertInvalid(String json) {
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, bytes(json), "100", "12"))
        .isInstanceOf(DetailItemImportException.class);
  }

  private static String envelope(String items) {
    return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":["
        + items
        + "]}}}}";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
