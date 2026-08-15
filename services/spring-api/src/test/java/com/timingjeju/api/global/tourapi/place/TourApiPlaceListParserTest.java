package com.timingjeju.api.global.tourapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiPlaceListParserTest {

  private final TourApiPlaceListParser parser = new TourApiPlaceListParser(new ObjectMapper());

  @Test
  void 두페이지_fixture의_장소필드와_page계약을_손실없이_파싱한다() {
    var first =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                page(
                    1,
                    2,
                    3,
                    validItem("1", "126.50", "33.50") + ',' + validItem("2", "126.60", "33.40"))));
    var second =
        parser.parse(
            SnapshotPayloadFormat.JSON, bytes(page(2, 2, 3, validItem("3", "126.70", "33.30"))));

    assertThat(first.pageNo()).isEqualTo(1);
    assertThat(first.numOfRows()).isEqualTo(2);
    assertThat(first.totalCount()).isEqualTo(3);
    assertThat(first.rawItemCount()).isEqualTo(2);
    assertThat(first.places()).hasSize(2);
    assertThat(first.places().getFirst())
        .extracting(
            "contentId",
            "contentTypeId",
            "title",
            "lDongRegnCd",
            "lDongSignguCd",
            "lclsSystm1",
            "address",
            "imageUrl")
        .containsExactly(
            "1",
            "12",
            "성산일출봉",
            "50",
            "50130",
            "VE",
            "제주특별자치도 서귀포시",
            "https://images.example.test/1.jpg");
    assertThat(first.places().getFirst().sourceModifiedAt()).isNotNull();
    assertThat(second.places()).singleElement().extracting("contentId").isEqualTo("3");
  }

  @Test
  void 잘못된_좌표행만_원인별로_reject하고_유효행은_보존한다() {
    String invalid = validItem("bad", "129.00", "33.50");
    var page =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(page(1, 10, 2, validItem("ok", "126.50", "33.50") + ',' + invalid)));

    assertThat(page.places()).singleElement().extracting("contentId").isEqualTo("ok");
    assertThat(page.rejectedReasons()).containsEntry(PlaceRejectReason.INVALID_COORDINATE, 1);
    assertThat(page.rawItemCount()).isEqualTo(2);
  }

  @Test
  void 제주_bbox_경계는_허용하고_범위밖_NaN_좌표는_reject한다() {
    String items =
        validItem("min", "126.00", "33.00")
            + ','
            + validItem("max", "127.00", "34.00")
            + ','
            + validItem("outside", "127.000001", "34.00")
            + ','
            + validItem("nan", "NaN", "33.50");
    var page = parser.parse(SnapshotPayloadFormat.JSON, bytes(page(1, 10, 4, items)));

    assertThat(page.places()).extracting("contentId").containsExactly("min", "max");
    assertThat(page.rejectedReasons()).containsEntry(PlaceRejectReason.INVALID_COORDINATE, 2);
  }

  @Test
  void 필수값_누락과_blank_HTML_title은_행단위로_원인만_reject한다() {
    String missingContentType =
        validItem("missing-type", "126.5", "33.5").replace("\"contenttypeid\":\"12\",", "");
    String blankTitle = validItem("blank", "126.5", "33.5").replace("성산일출봉", "   ");
    String htmlTitle = validItem("html", "126.5", "33.5").replace("성산일출봉", "<b>성산</b>");
    String escapedHtmlTitle =
        validItem("escaped-html", "126.5", "33.5").replace("성산일출봉", "&lt;b&gt;성산&lt;/b&gt;");
    var page =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                page(
                    1,
                    10,
                    4,
                    missingContentType
                        + ','
                        + blankTitle
                        + ','
                        + htmlTitle
                        + ','
                        + escapedHtmlTitle)));

    assertThat(page.places()).isEmpty();
    assertThat(page.rejectedReasons())
        .containsEntry(PlaceRejectReason.MISSING_REQUIRED_FIELD, 1)
        .containsEntry(PlaceRejectReason.INVALID_TITLE, 3);
  }

  @Test
  void 제주_scope밖_법정동과_법정동_필드의_잘못된_JSON타입은_값을_버리지_않고_reject한다() {
    String outOfScope =
        validItem("busan", "126.5", "33.5")
            .replace("\"lDongRegnCd\":\"50\"", "\"lDongRegnCd\":\"26\"");
    String wrongType =
        validItem("wrong-type", "126.5", "33.5")
            .replace("\"lDongSignguCd\":\"50130\"", "\"lDongSignguCd\":50130");

    var page =
        parser.parse(
            SnapshotPayloadFormat.JSON, bytes(page(1, 10, 2, outOfScope + ',' + wrongType)));

    assertThat(page.places()).isEmpty();
    assertThat(page.rejectedReasons())
        .containsEntry(PlaceRejectReason.OUT_OF_SCOPE, 1)
        .containsEntry(PlaceRejectReason.INVALID_FIELD, 1);
  }

  @Test
  void contentid와_contenttype의_DB_provenance_최대byte를_넘으면_행단위로_reject한다() {
    String longContentId = validItem("x".repeat(513), "126.5", "33.5");
    String longContentType =
        validItem("long-type", "126.5", "33.5")
            .replace("\"contenttypeid\":\"12\"", "\"contenttypeid\":\"" + "y".repeat(129) + "\"");

    var page =
        parser.parse(
            SnapshotPayloadFormat.JSON,
            bytes(page(1, 10, 2, longContentId + ',' + longContentType)));

    assertThat(page.places()).isEmpty();
    assertThat(page.rejectedReasons()).containsEntry(PlaceRejectReason.MISSING_REQUIRED_FIELD, 2);
  }

  @Test
  void error_envelope와_잘못된_page_metadata는_provider_detail없이_거부한다() {
    String sensitive = "provider-sensitive-detail";

    assertThatThrownBy(
            () ->
                parser.parse(
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\""
                            + sensitive
                            + "\"}}}")))
        .isInstanceOf(PlaceListImportException.class)
        .hasMessageNotContaining(sensitive);
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, bytes(page(0, 0, -1, ""))))
        .isInstanceOf(PlaceListImportException.class);
  }

  private static String page(int pageNo, int rows, int total, String items) {
    return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"pageNo\":"
        + pageNo
        + ",\"numOfRows\":"
        + rows
        + ",\"totalCount\":"
        + total
        + ",\"items\":{\"item\":["
        + items
        + "]}}}}";
  }

  private static String validItem(String id, String mapx, String mapy) {
    return "{\"contentid\":\""
        + id
        + "\",\"contenttypeid\":\"12\",\"title\":\"성산일출봉\",\"mapx\":\""
        + mapx
        + "\",\"mapy\":\""
        + mapy
        + "\",\"addr1\":\"제주특별자치도 서귀포시\",\"addr2\":\"성산읍\",\"firstimage\":\"https://images.example.test/1.jpg\",\"firstimage2\":\"https://images.example.test/1-thumb.jpg\",\"modifiedtime\":\"20260815123045\",\"lDongRegnCd\":\"50\",\"lDongSignguCd\":\"50130\",\"lclsSystm1\":\"VE\",\"lclsSystm2\":\"VE01\",\"lclsSystm3\":\"VE0101\"}";
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
