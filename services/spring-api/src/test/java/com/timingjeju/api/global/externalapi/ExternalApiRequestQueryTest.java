package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExternalApiRequestQueryTest {

  @Test
  void provider의_leading_underscore_query만_허용하고_구분자와_credential은_거부한다() {
    ExternalApiRequest request = request(Map.of("_type", "json", "nodeId", "JEP123"));

    assertThat(request.queryParameters())
        .containsExactlyInAnyOrderEntriesOf(Map.of("_type", "json", "nodeId", "JEP123"));
    assertThatThrownBy(() -> request(Map.of("bad name", "value")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(Map.of("bad&name", "value")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(Map.of("serviceKey", "secret")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request(Map.of("_type", "json\nxml")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ExternalApiRequest request(Map<String, String> query) {
    return ExternalApiRequest.get(
        ExternalApiOperation.TAGO_ARRIVAL,
        "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList",
        query,
        ExternalApiResponseFormat.JSON);
  }
}
