package com.timingjeju.api.global.tago.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TagoRouteParserTest {
  private final TagoRouteParser parser = new TagoRouteParser(new ObjectMapper());

  @Test
  void JSON과_XML_공식_envelope의_route_detail과_stop_sequence를_동일하게_파싱한다() {
    byte[] json =
        envelope(
            "{\"routeid\":\"JEB405410111\",\"routeno\":\"101\",\"routetp\":\"급행\",\"startnodenm\":\"공항\",\"endnodenm\":\"성산\"}",
            1,
            1,
            1);
    byte[] xml =
        xmlEnvelope(
            "<item><routeid>JEB405410111</routeid><routeno>101</routeno><routetp>급행</routetp><startnodenm>공항</startnodenm><endnodenm>성산</endnodenm></item>",
            1,
            1,
            1);

    assertThat(parser.parseRouteDetail(SnapshotPayloadFormat.JSON, json, "39", "JEB405410111"))
        .isEqualTo(parser.parseRouteDetail(SnapshotPayloadFormat.XML, xml, "39", "JEB405410111"));

    byte[] stops =
        envelope(
            "[{\"nodeid\":\"STOP-1\",\"nodenm\":\"공항\",\"nodeord\":\"1\"},{\"nodeid\":\"STOP-2\",\"nodenm\":\"성산\",\"nodeord\":\"2\"}]",
            1,
            100,
            2);
    assertThat(
            parser
                .parseRouteStops(SnapshotPayloadFormat.JSON, stops, "39", "JEB405410111", 1)
                .stops())
        .extracting(stop -> stop.stopSequence())
        .containsExactly(1, 2);
  }

  @Test
  void sequence는_positive_unique_contiguous이고_route와_city_scope가_일치해야_한다() {
    byte[] zero =
        envelope("{\"nodeid\":\"STOP-1\",\"nodenm\":\"공항\",\"nodeord\":\"0\"}", 1, 100, 1);
    byte[] gap =
        envelope(
            "[{\"nodeid\":\"STOP-1\",\"nodenm\":\"공항\",\"nodeord\":\"1\"},{\"nodeid\":\"STOP-2\",\"nodenm\":\"성산\",\"nodeord\":\"3\"}]",
            1,
            100,
            2);

    assertThatThrownBy(() -> parser.parseRouteStops(SnapshotPayloadFormat.JSON, zero, "39", "R", 1))
        .isInstanceOf(TagoRouteImportException.class);
    assertThatThrownBy(() -> parser.parseRouteStops(SnapshotPayloadFormat.JSON, gap, "39", "R", 1))
        .isInstanceOf(TagoRouteImportException.class);
  }

  @Test
  void provider_error_wrong_scalar_type과_official_field_max를_거부한다() {
    byte[] error =
        "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"KEY ERROR\"},\"body\":{\"items\":{\"item\":[]},\"pageNo\":1,\"numOfRows\":100,\"totalCount\":0}}}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] wrongType =
        envelope("{\"routeid\":101,\"routeno\":\"101\",\"routetp\":\"급행\"}", 1, 100, 1);
    byte[] tooLong =
        envelope(
            "{\"routeid\":\"" + "R".repeat(31) + "\",\"routeno\":\"101\",\"routetp\":\"급행\"}",
            1,
            100,
            1);

    assertThatThrownBy(
            () -> parser.parseRouteList(SnapshotPayloadFormat.JSON, error, "39", "101", 1))
        .isInstanceOf(TagoRouteImportException.class);
    assertThatThrownBy(
            () -> parser.parseRouteList(SnapshotPayloadFormat.JSON, wrongType, "39", "101", 1))
        .isInstanceOf(TagoRouteImportException.class);
    assertThatThrownBy(
            () -> parser.parseRouteList(SnapshotPayloadFormat.JSON, tooLong, "39", "101", 1))
        .isInstanceOf(TagoRouteImportException.class);
  }

  private static byte[] envelope(String item, int pageNo, int rows, int total) {
    return ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},\"body\":{\"items\":{\"item\":"
            + item
            + "},\"pageNo\":"
            + pageNo
            + ",\"numOfRows\":"
            + rows
            + ",\"totalCount\":"
            + total
            + "}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] xmlEnvelope(String item, int pageNo, int rows, int total) {
    return ("<response><header><resultCode>00</resultCode><resultMsg>OK</resultMsg></header><body><items>"
            + item
            + "</items><pageNo>"
            + pageNo
            + "</pageNo><numOfRows>"
            + rows
            + "</numOfRows><totalCount>"
            + total
            + "</totalCount></body></response>")
        .getBytes(StandardCharsets.UTF_8);
  }
}
