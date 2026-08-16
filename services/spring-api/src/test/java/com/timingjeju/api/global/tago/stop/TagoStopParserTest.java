package com.timingjeju.api.global.tago.stop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TagoStopParserTest {
  private final TagoStopParser parser = new TagoStopParser(new ObjectMapper());

  @Test
  void JSON과_XML의_공식_envelope를_같은_city_code로_정규화한다() {
    byte[] json =
        """
        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
        "body":{"items":{"item":[{"citycode":"39","cityname":"제주특별자치도"}]},
        "numOfRows":10,"pageNo":1,"totalCount":1}}}
        """
            .getBytes(StandardCharsets.UTF_8);
    byte[] xml =
        """
        <response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
        <body><items><item><citycode>39</citycode><cityname>제주특별자치도</cityname></item></items>
        <numOfRows>10</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body></response>
        """
            .getBytes(StandardCharsets.UTF_8);

    assertThat(parser.parseCityCodes(SnapshotPayloadFormat.JSON, json))
        .containsExactly(new TagoCityCode("39", "제주특별자치도"));
    assertThat(parser.parseCityCodes(SnapshotPayloadFormat.XML, xml))
        .containsExactly(new TagoCityCode("39", "제주특별자치도"));
  }

  @Test
  void 제주가_없거나_error_code이면_city_discovery를_거부한다() {
    byte[] noJeju = cityJson("11", "서울특별시");
    byte[] error =
        "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"KEY ERROR\"},\"body\":{}}}"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> parser.discoverJejuCityCode(SnapshotPayloadFormat.JSON, noJeju))
        .isInstanceOf(TagoStopImportException.class);
    assertThatThrownBy(() -> parser.parseCityCodes(SnapshotPayloadFormat.JSON, error))
        .isInstanceOf(TagoStopImportException.class);
  }

  @Test
  void station의_type_좌표_제주_bounds와_duplicate_node를_엄격히_검증한다() {
    byte[] valid = stationJson("39", "JEP123", "제주공항", "405", "33.507", "126.493");
    var page = parser.parseStations(SnapshotPayloadFormat.JSON, valid, "39", 1);

    assertThat(page.totalCount()).isEqualTo(1);
    assertThat(page.stations())
        .containsExactly(new TagoStation("39", "JEP123", "405", "제주공항", 126.493, 33.507));

    byte[] wrongType = stationJson("39", "JEP123", "제주공항", "405", "north", "126.493");
    byte[] outside = stationJson("39", "JEP123", "제주공항", "405", "37.5", "126.493");
    byte[] duplicate =
        new String(valid, StandardCharsets.UTF_8)
            .replace(
                "]},\"numOfRows\"",
                ",{\"citycode\":\"39\",\"nodeid\":\"JEP123\",\"nodenm\":\"다른 이름\",\"nodeno\":\"406\",\"gpslati\":\"33.5\",\"gpslong\":\"126.5\"}]},\"numOfRows\"")
            .replace("\"totalCount\":1", "\"totalCount\":2")
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> parser.parseStations(SnapshotPayloadFormat.JSON, wrongType, "39", 1))
        .isInstanceOf(TagoStopImportException.class);
    assertThatThrownBy(() -> parser.parseStations(SnapshotPayloadFormat.JSON, outside, "39", 1))
        .isInstanceOf(TagoStopImportException.class);
    assertThatThrownBy(() -> parser.parseStations(SnapshotPayloadFormat.JSON, duplicate, "39", 1))
        .isInstanceOf(TagoStopImportException.class);
  }

  private static byte[] cityJson(String code, String name) {
    return ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},\"body\":{\"items\":{\"item\":[{\"citycode\":\""
            + code
            + "\",\"cityname\":\""
            + name
            + "\"}]},\"numOfRows\":10,\"pageNo\":1,\"totalCount\":1}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] stationJson(
      String city, String node, String name, String number, String latitude, String longitude) {
    return ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},\"body\":{\"items\":{\"item\":[{\"citycode\":\""
            + city
            + "\",\"nodeid\":\""
            + node
            + "\",\"nodenm\":\""
            + name
            + "\",\"nodeno\":\""
            + number
            + "\",\"gpslati\":\""
            + latitude
            + "\",\"gpslong\":\""
            + longitude
            + "\"}]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":1}}}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
