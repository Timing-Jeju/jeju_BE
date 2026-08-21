package com.timingjeju.api.global.tourapi.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiDetailImageParserTest {
  private final TourApiDetailImageParser parser = new TourApiDetailImageParser(new ObjectMapper());

  @Test
  void source_id와_http_https_대표_썸네일_저작권_license를_응답순서대로_보존한다() {
    var page =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                page(
                    1,
                    100,
                    2,
                    "{\"contentid\":\"100\",\"serialnum\":\"IMG-2\",\"originimgurl\":\"https://img.example.test/2.jpg\",\"smallimageurl\":\"https://img.example.test/2-small.jpg\",\"imgname\":\"두 번째\",\"cpyrhtDivCd\":\"Type1\",\"copyrightowner\":\"한국관광공사\",\"license\":\"공공누리 제1유형\"},"
                        + "{\"contentid\":\"100\",\"originimgurl\":\"http://img.example.test/1.jpg\",\"smallimageurl\":\"http://img.example.test/1-small.jpg\",\"imgname\":\"첫 번째\"}")),
            "100");

    assertThat(page.pageNo()).isEqualTo(1);
    assertThat(page.totalCount()).isEqualTo(2);
    assertThat(page.images()).extracting("sourceImageId").containsExactly("IMG-2", null);
    assertThat(page.images()).extracting("displayOrder").containsExactly(1, 2);
    assertThat(page.images().getFirst().imageUrl()).isEqualTo("https://img.example.test/2.jpg");
    assertThat(page.images().getFirst().thumbnailUrl())
        .isEqualTo("https://img.example.test/2-small.jpg");
    assertThat(page.images().get(1).imageUrl()).isEqualTo("http://img.example.test/1.jpg");
    assertThat(page.images().get(1).thumbnailUrl())
        .isEqualTo("http://img.example.test/1-small.jpg");
    assertThat(page.images().getFirst().copyrightCode()).isEqualTo("Type1");
    assertThat(page.images().getFirst().copyrightOwner()).isEqualTo("한국관광공사");
    assertThat(page.images().getFirst().licenseText()).isEqualTo("공공누리 제1유형");
  }

  @Test
  void 이미지가_없는_totalCount_0은_empty_page로_보존한다() {
    String empty =
        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"pageNo\":1,\"numOfRows\":100,\"totalCount\":0,\"items\":\"\"}}}";

    var page = parser.parse(SnapshotPayloadFormat.JSON, bytes(empty), "100");

    assertThat(page.images()).isEmpty();
    assertThat(page.rawItemCount()).isZero();
  }

  @Test
  void http_https_상대_URL_userinfo_비문자_누락된_host_비허용_fragment를_거부한다() {
    assertInvalid(item("ftp://img.example.test/a.jpg", null));
    assertInvalid(item("/relative.jpg", null));
    assertInvalid(item("https:///img.example.test/path", null));
    assertInvalid(item("https://img.example.test/path#section", null));
    assertInvalid(item("https://user@img.example.test/a.jpg", null));
    assertInvalid(item("https://img.example.test/" + "가".repeat(2800), null));
    assertInvalid("{\"contentid\":\"100\",\"originimgurl\":123,\"serialnum\":\"IMG-1\"}");
  }

  @Test
  void URL_UTF8_8192byte는_허용하고_8193byte는_거부한다() {
    String prefix = "https://img.example.test/";
    String exact = prefix + "a".repeat(8192 - prefix.getBytes(StandardCharsets.UTF_8).length);
    String tooLong = exact + "a";

    var accepted =
        parser.parse(
            SnapshotPayloadFormat.JSON, bytes(page(1, 100, 1, item(exact, "BOUNDARY"))), "100");

    assertThat(accepted.images().getFirst().imageUrl()).isEqualTo(exact);
    assertInvalid(item(tooLong, "TOO-LONG"));
  }

  @Test
  void 동일_source_id나_URL_중복과_contentid_mismatch를_거부한다() {
    assertInvalid(
        item("https://img.example.test/a.jpg", "SAME")
            + ","
            + item("https://img.example.test/b.jpg", "SAME"));
    assertInvalid(
        item("https://img.example.test/a.jpg", "A")
            + ","
            + item("https://img.example.test/a.jpg", "B"));
    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        page(
                            1,
                            100,
                            1,
                            "{\"contentid\":\"other\",\"originimgurl\":\"https://img.example.test/a.jpg\"}")),
                    "100"))
        .isInstanceOf(PlaceImageImportException.class);
  }

  private void assertInvalid(String items) {
    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON, bytes(page(1, 100, count(items), items)), "100"))
        .isInstanceOf(PlaceImageImportException.class);
  }

  private static String item(String url, String sourceId) {
    return "{\"contentid\":\"100\",\"originimgurl\":\""
        + url
        + "\""
        + (sourceId == null ? "" : ",\"serialnum\":\"" + sourceId + "\"")
        + "}";
  }

  private static int count(String items) {
    return items.split("\\\"contentid\\\"", -1).length - 1;
  }

  private static String page(int pageNo, int numOfRows, int totalCount, String items) {
    return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"pageNo\":"
        + pageNo
        + ",\"numOfRows\":"
        + numOfRows
        + ",\"totalCount\":"
        + totalCount
        + ",\"items\":{\"item\":["
        + items
        + "]}}}}";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
