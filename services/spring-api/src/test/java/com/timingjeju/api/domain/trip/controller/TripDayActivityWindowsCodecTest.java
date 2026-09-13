package com.timingjeju.api.domain.trip.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.trip.TripException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TripDayActivityWindowsCodecTest {
  private final TripDayActivityWindowsRequestCodec codec =
      new TripDayActivityWindowsRequestCodec(new ObjectMapper());
  private static final String DAY =
      "{\"dayId\":\"45000000-0000-4000-8000-000000000001\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}";

  @Test
  void 정확한_시분과_Day_ID를_구조화한다() {
    var decoded = codec.decode(bytes("{\"days\":[" + DAY + "]}"));
    assertThat(decoded.days()).hasSize(1);
    assertThat(decoded.days().getFirst().startTime()).isEqualTo(LocalTime.of(9, 0));
    assertThat(decoded.days().getFirst().endTime()).isEqualTo(LocalTime.of(18, 0));
  }

  @Test
  void 미지필드_중복키_null_누락_잘못된타입은_구조오류다() {
    for (String body :
        List.of(
            "null",
            "{}",
            "{\"days\":null}",
            "{\"days\":{}}",
            "{\"days\":[null]}",
            "{\"days\":[],\"gps\":{}}",
            "{\"days\":[],\"days\":[]}",
            "{\"days\":[" + DAY.replace("\"09:00\"", "900") + "]}",
            "{\"days\":[" + DAY.replace("\"09:00\"", "null") + "]}",
            "{\"days\":[" + DAY.replace("\"startTime\":\"09:00\",", "") + "]}",
            "{\"days\":[" + DAY.replace("{", "{\"latitude\":33,") + "]}",
            "{\"days\":[" + DAY + "]} {}")) {
      assertCode(body, "INVALID_REQUEST");
    }
  }

  @Test
  void 초포함_잘못된시분_역전_동일시간_중복Day는_거부한다() {
    for (String time : List.of("9:00", "09:00:00", "24:00", "09:60", " 09:00", "18:00", "19:00")) {
      assertCode("{\"days\":[" + DAY.replace("09:00", time) + "]}", "TRIP_CONSTRAINT_VIOLATION");
    }
    assertCode("{\"days\":[]}", "TRIP_CONSTRAINT_VIOLATION");
    assertCode("{\"days\":[" + DAY + "," + DAY + "]}", "TRIP_CONSTRAINT_VIOLATION");
  }

  private void assertCode(String body, String code) {
    assertThatThrownBy(() -> codec.decode(bytes(body)))
        .isInstanceOfSatisfying(
            TripException.class, failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }
}
