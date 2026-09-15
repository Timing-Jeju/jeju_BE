package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class TripPlannerConditionsCodecTest {
  @Test
  void 닫힌_ID_조건을_해석하고_원문과_좌표_필드를_거부한다() {
    var codec = new TripPlannerConditionsCodec(JsonMapper.builder().build());
    assertThat(codec.decode(bytes("{\"dayAnchors\":[],\"styleCodes\":[\"cafe\"]}")).styleCodes())
        .containsExactly("cafe");
    for (String body :
        new String[] {
          "{\"dayAnchors\":[],\"styleCodes\":[],\"prompt\":\"원문\"}",
          "{\"dayAnchors\":[],\"styleCodes\":[],\"styleCodes\":[]}",
          "{\"dayAnchors\":[],\"styleCodes\":[]} {}",
          "{\"dayAnchors\":[],\"styleCodes\":null}",
          "{\"dayAnchors\":[{\"dayId\":\"1-1-1-1-1\",\"lodgingPlaceId\":null}],\"styleCodes\":[]}"
        }) assertThatThrownBy(() -> codec.decode(bytes(body))).isInstanceOf(RuntimeException.class);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
