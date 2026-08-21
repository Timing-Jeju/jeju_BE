package com.timingjeju.api.global.tourapi.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import com.timingjeju.api.global.text.JsoupPublicPlainTextNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiPlaceDetailParserTest {

  private final JsoupPublicPlainTextNormalizer publicText = new JsoupPublicPlainTextNormalizer();
  private final TourApiDetailCommonParser common =
      new TourApiDetailCommonParser(
          new ObjectMapper(), new OverviewPlainTextSanitizer(), publicText);
  private final TourApiDetailIntroParser intro =
      new TourApiDetailIntroParser(new ObjectMapper(), publicText);

  @Test
  void common은_overview_원문을_보존하고_allowlist_plain_text만_노출한다() {
    String raw =
        "<p>성산 <strong>일출봉</strong><br>안내</p><script>alert('x')</script>"
            + "<a href=\"javascript:alert(1)\" onclick=\"evil()\">링크</a>";

    var parsed = common.parse(SnapshotPayloadFormat.JSON, bytes(commonEnvelope(raw)));

    assertThat(parsed.contentId()).isEqualTo("100");
    assertThat(parsed.contentTypeId()).isEqualTo("12");
    assertThat(parsed.phone()).isEqualTo("064-123-4567");
    assertThat(parsed.overviewRaw()).isEqualTo(raw);
    assertThat(parsed.overviewPlainText()).isEqualTo("성산 일출봉\n안내\n링크");
    assertThat(parsed.overviewPlainText())
        .doesNotContain("script", "alert", "onclick", "javascript");
  }

  @Test
  void common의_optional이_없으면_null이고_깨진_HTML도_실행요소없이_text를_복구한다() {
    var parsed =
        common.parse(
            SnapshotPayloadFormat.JSON,
            bytes(commonEnvelope("<p>열린 문단<b>강조<script>evil()</script>끝")));

    assertThat(parsed.homepageUrl()).isNull();
    assertThat(parsed.overviewPlainText()).isEqualTo("열린 문단 강조 끝");
    assertThat(parsed.overviewRaw()).contains("<script>");
  }

  @Test
  void intro는_관광지_숙박_음식점별_원문필드를_손실없이_정규화한다() {
    var attraction =
        intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("12", attraction())));
    var lodging = intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("32", lodging())));
    var food = intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("39", food())));

    assertThat(attraction)
        .extracting("phone", "operatingHoursText", "closedDaysText", "parkingText", "petPolicyText")
        .containsExactly("064-111-1111", "09:00~18:00", "월요일", "주차 가능", "반려동물 불가");
    assertThat(lodging)
        .extracting(
            "phone", "operatingHoursText", "closedDaysText", "parkingText", "reservationInfoText")
        .containsExactly("064-222-2222", "15:00 / 11:00", null, "주차장", "전화 예약");
    assertThat(food)
        .extracting(
            "phone", "operatingHoursText", "closedDaysText", "parkingText", "reservationInfoText")
        .containsExactly("064-333-3333", "10:00~20:00", "화요일", "무료 주차", "예약 가능");
    assertThat(attraction.introAttributes()).containsEntry("expguide", "해설 운영");
    assertThat(lodging.introAttributes()).containsEntry("roomcount", "20");
    assertThat(food.introAttributes()).containsEntry("firstmenu", "갈치조림");
  }

  @Test
  void 외부_detail의_공개_text는_ingestion에서_plain_text_1000_code_point로_정규화한다() {
    String dangerous =
        "<script>secret()</script><style>.x{}</style><b onclick='evil()'>운영&nbsp; 안내</b>"
            + "\u0000  오전\n 9시 "
            + "🍊".repeat(1000);
    String fields =
        jsonFields(
            Map.of(
                "infocenter", dangerous,
                "usetime", dangerous,
                "restdate", dangerous,
                "parking", dangerous));

    var parsed = intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("12", fields)));
    var commonParsed =
        common.parse(
            SnapshotPayloadFormat.JSON,
            bytes(
                envelope(
                    "\"contentid\":\"100\",\"contenttypeid\":\"12\",\"tel\":\""
                        + json(dangerous)
                        + "\",\"overview\":\"안내\"")));

    assertThat(parsed.phone()).startsWith("운영 안내 오전 9시").doesNotContain("secret", "onclick");
    assertThat(parsed.operatingHoursText()).isEqualTo(parsed.phone());
    assertThat(parsed.introAttributes().get("infocenter")).isEqualTo(parsed.phone());
    assertThat(parsed.phone().codePointCount(0, parsed.phone().length())).isEqualTo(1000);
    assertThat(commonParsed.phone()).isEqualTo(parsed.phone());
  }

  @Test
  void 공식_detailIntro2_관광지_숙박_음식점_원천필드를_모두_introAttributes에_보존한다() {
    Map<String, String[]> matrix = new LinkedHashMap<>();
    matrix.put(
        "12",
        new String[] {
          "accomcount",
          "chkbabycarriage",
          "chkcreditcard",
          "chkpet",
          "expagerange",
          "expguide",
          "heritage1",
          "heritage2",
          "heritage3",
          "infocenter",
          "opendate",
          "parking",
          "restdate",
          "useseason",
          "usetime"
        });
    matrix.put(
        "32",
        new String[] {
          "barbecue",
          "beauty",
          "benikia",
          "beverage",
          "bicycle",
          "campfire",
          "checkintime",
          "checkouttime",
          "chkcooking",
          "fitness",
          "foodplace",
          "goodstay",
          "hanok",
          "infocenterlodging",
          "karaoke",
          "parkinglodging",
          "pickup",
          "publicbath",
          "publicpc",
          "refundregulation",
          "reservationlodging",
          "reservationurl",
          "roomcount",
          "roomtype",
          "sauna",
          "scalelodging",
          "seminar",
          "sports",
          "subfacility"
        });
    matrix.put(
        "39",
        new String[] {
          "chkcreditcardfood",
          "discountinfofood",
          "firstmenu",
          "infocenterfood",
          "kidsfacility",
          "lcnsno",
          "opendatefood",
          "opentimefood",
          "packing",
          "parkingfood",
          "reservationfood",
          "restdatefood",
          "scalefood",
          "seat",
          "smoking",
          "treatmenu"
        });

    assertSoftly(
        softly ->
            matrix.forEach(
                (contentType, fields) -> {
                  Map<String, String> expected = new LinkedHashMap<>();
                  for (String field : fields) {
                    expected.put(field, field + "-원문");
                  }

                  var parsed =
                      intro.parse(
                          SnapshotPayloadFormat.JSON,
                          bytes(introEnvelope(contentType, jsonFields(expected))));

                  softly
                      .assertThat(parsed.introAttributes())
                      .as("contentTypeId=%s 공식 원천 필드", contentType)
                      .containsExactlyInAnyOrderEntriesOf(expected);
                }));
  }

  @Test
  void intro_optional_누락은_null로_보존하고_지원하지_않는_content_type과_잘못된_타입은_거부한다() {
    var parsed = intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("39", "")));
    assertThat(parsed.phone()).isNull();
    assertThat(parsed.introAttributes()).isEmpty();

    assertThatThrownBy(
            () -> intro.parse(SnapshotPayloadFormat.JSON, bytes(introEnvelope("99", ""))))
        .isInstanceOf(PlaceDetailImportException.class);
    assertThatThrownBy(
            () ->
                intro.parse(
                    SnapshotPayloadFormat.JSON, bytes(introEnvelope("12", "\"infocenter\":123"))))
        .isInstanceOf(PlaceDetailImportException.class);
  }

  private static String commonEnvelope(String overview) {
    return envelope(
        "\"contentid\":\"100\",\"contenttypeid\":\"12\","
            + "\"tel\":\"064-123-4567\",\"modifiedtime\":\"20260815123045\","
            + "\"overview\":\""
            + json(overview)
            + "\"");
  }

  private static String introEnvelope(String type, String fields) {
    String suffix = fields.isBlank() ? "" : "," + fields;
    return envelope("\"contentid\":\"100\",\"contenttypeid\":\"" + type + "\"" + suffix);
  }

  private static String envelope(String item) {
    return "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":[{"
        + item
        + "}]}}}}";
  }

  private static String attraction() {
    return "\"infocenter\":\"064-111-1111\",\"usetime\":\"09:00~18:00\",\"restdate\":\"월요일\",\"parking\":\"주차 가능\",\"chkpet\":\"반려동물 불가\",\"expguide\":\"해설 운영\"";
  }

  private static String lodging() {
    return "\"infocenterlodging\":\"064-222-2222\",\"checkintime\":\"15:00\",\"checkouttime\":\"11:00\",\"parkinglodging\":\"주차장\",\"reservationlodging\":\"전화 예약\",\"roomcount\":\"20\"";
  }

  private static String food() {
    return "\"infocenterfood\":\"064-333-3333\",\"opentimefood\":\"10:00~20:00\",\"restdatefood\":\"화요일\",\"parkingfood\":\"무료 주차\",\"reservationfood\":\"예약 가능\",\"firstmenu\":\"갈치조림\"";
  }

  private static String jsonFields(Map<String, String> values) {
    return values.entrySet().stream()
        .map(entry -> "\"" + entry.getKey() + "\":\"" + json(entry.getValue()) + "\"")
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String json(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\u0000", "\\u0000")
        .replace("\n", "\\n");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
