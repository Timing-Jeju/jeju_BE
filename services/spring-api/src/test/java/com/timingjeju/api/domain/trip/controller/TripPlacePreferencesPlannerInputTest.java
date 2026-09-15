package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.trip.dto.request.UpdateTripPlacePreferencesRequest;
import com.timingjeju.api.domain.trip.dto.response.TripPlacePreferenceResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class TripPlacePreferencesPlannerInputTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void 선택방문_체류시간은_입력과_응답에서_왕복한다() {
    var item =
        mapper
            .readValue(body("90"), UpdateTripPlacePreferencesRequest.class)
            .toCommand()
            .items()
            .getFirst();
    assertThat(item.requestedStayMinutes()).isEqualTo(90);
    assertThat(
            mapper
                .valueToTree(TripPlacePreferenceResponse.from(item))
                .get("requestedStayMinutes")
                .intValue())
        .isEqualTo(90);
  }

  @Test
  void nullable_체류시간은_추천값으로_변조하지_않는다() {
    var item =
        mapper
            .readValue(body("null"), UpdateTripPlacePreferencesRequest.class)
            .toCommand()
            .items()
            .getFirst();
    assertThat(item.requestedStayMinutes()).isNull();
  }

  @Test
  void 문자열과_실수_체류시간은_정수로_강제변환하지_않는다() {
    for (String value : new String[] {"\"90\"", "90.0", "true", "2147483648"}) {
      assertThatThrownBy(
              () ->
                  mapper
                      .readValue(body(value), UpdateTripPlacePreferencesRequest.class)
                      .toCommand())
          .isInstanceOf(RuntimeException.class);
    }
  }

  private static String body(String stay) {
    return "{\"items\":[{\"placeId\":\"53000000-0000-0000-0000-000000000001\",\"type\":\"preferred\",\"targetDayNo\":1,\"priority\":50,\"requestedStayMinutes\":"
        + stay
        + "}]}";
  }
}
