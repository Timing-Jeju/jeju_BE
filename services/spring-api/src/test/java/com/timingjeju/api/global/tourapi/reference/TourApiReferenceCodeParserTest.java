package com.timingjeju.api.global.tourapi.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.reference.ReferenceCode;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeOperation;
import com.timingjeju.api.application.tourapi.reference.ReferenceCodeSyncException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TourApiReferenceCodeParserTest {

  private final TourApiReferenceCodeParser parser =
      new TourApiReferenceCodeParser(new ObjectMapper());

  @Test
  void JSON_법정동_envelope에서_제주_루트와_시군구를_계층으로_파싱한다() {
    var codes =
        parser.parse(
            ReferenceCodeOperation.LDONG,
            SnapshotPayloadFormat.JSON,
            bytes(
                """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                  "body":{"items":{"item":[
                    {"lDongRegnCd":"50","lDongRegnNm":"제주특별자치도","lDongSignguCd":"50110","lDongSignguNm":"제주시"},
                    {"lDongRegnCd":"50","lDongRegnNm":"제주특별자치도","lDongSignguCd":"50130","lDongSignguNm":"서귀포시"}
                  ]}}}}
                """));

    assertThat(codes)
        .extracting(
            ReferenceCode::codeType,
            ReferenceCode::externalCode,
            ReferenceCode::parentExternalCode,
            ReferenceCode::name)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("ldong-region", "50", null, "제주특별자치도"),
            org.assertj.core.groups.Tuple.tuple("ldong-signgu", "50110", "50", "제주시"),
            org.assertj.core.groups.Tuple.tuple("ldong-signgu", "50130", "50", "서귀포시"));
  }

  @Test
  void XML_관광분류_envelope에서_세단계_부모관계를_파싱한다() {
    var codes =
        parser.parse(
            ReferenceCodeOperation.CLASSIFICATION,
            SnapshotPayloadFormat.XML,
            bytes(
                """
                <response><header><resultCode>0000</resultCode><resultMsg>OK</resultMsg></header>
                  <body><items><item>
                    <lclsSystm1>AC</lclsSystm1><lclsSystm1Nm>레포츠</lclsSystm1Nm>
                    <lclsSystm2>AC01</lclsSystm2><lclsSystm2Nm>육상 레포츠</lclsSystm2Nm>
                    <lclsSystm3>AC0101</lclsSystm3><lclsSystm3Nm>트레킹</lclsSystm3Nm>
                  </item></items></body>
                </response>
                """));

    assertThat(codes)
        .extracting(
            ReferenceCode::codeType, ReferenceCode::externalCode, ReferenceCode::parentExternalCode)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("lcls-1", "AC", null),
            org.assertj.core.groups.Tuple.tuple("lcls-2", "AC01", "AC"),
            org.assertj.core.groups.Tuple.tuple("lcls-3", "AC0101", "AC01"));
  }

  @Test
  void JSON과_XML_오류_envelope는_provider_message를_노출하지_않고_거부한다() {
    String sensitiveDetail = "provider-sensitive-detail";

    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.LDONG,
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\""
                            + sensitiveDetail
                            + "\"}}}")))
        .isInstanceOf(ReferenceCodeSyncException.class)
        .hasMessageNotContaining(sensitiveDetail);
    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.CLASSIFICATION,
                    SnapshotPayloadFormat.XML,
                    bytes(
                        "<response><header><resultCode>30</resultCode><resultMsg>"
                            + sensitiveDetail
                            + "</resultMsg></header></response>")))
        .isInstanceOf(ReferenceCodeSyncException.class)
        .hasMessageNotContaining(sensitiveDetail);
  }

  @Test
  void 빈_items와_필수값_누락은_전체_envelope를_거부한다() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.LDONG,
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":[]}}}}")))
        .isInstanceOf(ReferenceCodeSyncException.class);
    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.CLASSIFICATION,
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":[{\"lclsSystm1\":\"AC\"}]}}}}")))
        .isInstanceOf(ReferenceCodeSyncException.class);
  }

  @Test
  void 제주_루트가_없거나_하위코드의_부모가_없으면_전체_batch를_거부한다() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.LDONG,
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":[{\"lDongRegnCd\":\"26\",\"lDongRegnNm\":\"부산광역시\"}]}}}}")))
        .isInstanceOf(ReferenceCodeSyncException.class);
    assertThatThrownBy(
            () ->
                parser.parse(
                    ReferenceCodeOperation.CLASSIFICATION,
                    SnapshotPayloadFormat.JSON,
                    bytes(
                        "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":[{\"lclsSystm2\":\"AC01\",\"lclsSystm2Nm\":\"육상 레포츠\"}]}}}}")))
        .isInstanceOf(ReferenceCodeSyncException.class);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
