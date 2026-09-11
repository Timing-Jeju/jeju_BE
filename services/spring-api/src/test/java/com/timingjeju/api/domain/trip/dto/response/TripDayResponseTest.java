package com.timingjeju.api.domain.trip.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.trip.TripDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TripDayResponseTest {
  @Test
  void 기존_DB의_초단위시간을_분단위로_몰래_절삭하지않는다() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TripDayResponse.from(
                    new TripDay(
                        UUID.randomUUID(),
                        1,
                        LocalDate.of(2026, 9, 1),
                        LocalTime.of(9, 0, 30),
                        LocalTime.of(18, 0))))
        .isInstanceOfSatisfying(
            com.timingjeju.api.application.trip.TripException.class,
            failure -> assertThat(failure.code()).isEqualTo("TRIP_DATA_UNAVAILABLE"));
  }

  @Test
  void 활동시간은_분단위_문자열이며_미입력도_null_필드를_명시한다() {
    var mapper = new ObjectMapper();
    var id = UUID.randomUUID();
    var date = LocalDate.of(2026, 9, 1);
    var entered =
        mapper.valueToTree(
            TripDayResponse.from(
                new TripDay(id, 1, date, LocalTime.of(9, 0), LocalTime.of(18, 5))));
    assertThat(entered.path("activityStartTime").asText()).isEqualTo("09:00");
    assertThat(entered.path("activityEndTime").asText()).isEqualTo("18:05");
    var empty = mapper.valueToTree(TripDayResponse.from(new TripDay(id, 1, date)));
    assertThat(empty.has("activityStartTime")).isTrue();
    assertThat(empty.path("activityStartTime").isNull()).isTrue();
    assertThat(empty.has("activityEndTime")).isTrue();
    assertThat(empty.path("activityEndTime").isNull()).isTrue();
  }
}
