package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TagoArrivalParserTest {
  private final TagoArrivalParser parser = new TagoArrivalParser(new ObjectMapper());

  @Test
  void 공식_envelope의_도착초와_남은정류장을_엄격히_정규화한다() {
    byte[] payload = success("321", "4");

    assertThat(parser.parse(SnapshotPayloadFormat.JSON, payload))
        .containsExactly(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4));
  }

  @Test
  void provider_97과_성공_empty를_구분한다() {
    byte[] quota =
        "{\"response\":{\"header\":{\"resultCode\":\"97\",\"resultMsg\":\"LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR.\"},\"body\":{}}}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] empty =
        "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},\"body\":{\"items\":{},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":0}}}"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, quota))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.RATE_LIMITED));
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, empty))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.EMPTY_RESULT));
  }

  @Test
  void 도착초의_음수와_하루초과_및_남은정류장_음수를_거부한다() {
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("-1", "4")))
        .isInstanceOf(TagoArrivalException.class);
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("86401", "4")))
        .isInstanceOf(TagoArrivalException.class);
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("321", "-1")))
        .isInstanceOf(TagoArrivalException.class);
  }

  private static byte[] success(String arrivalSeconds, String remainingStops) {
    return ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},\"body\":{\"items\":{\"item\":[{\"routeid\":\"JER001\",\"routeno\":\"201\",\"routetp\":\"간선버스\",\"vehicletp\":\"일반차량\",\"arrtime\":\""
            + arrivalSeconds
            + "\",\"arrprevstationcnt\":\""
            + remainingStops
            + "\"}]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":1}}}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
