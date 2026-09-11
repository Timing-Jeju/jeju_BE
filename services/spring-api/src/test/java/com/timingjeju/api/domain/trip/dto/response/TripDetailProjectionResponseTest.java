package com.timingjeju.api.domain.trip.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripDay;
import com.timingjeju.api.application.trip.TripTransportMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TripDetailProjectionResponseTest {
  @Test
  void 입력한_숙소와_교통은_write와_동일한_필드와_시각으로_직렬화한다() {
    var base = emptyTrip();
    var arrival =
        new com.timingjeju.api.application.transportevent.TransportEvent(
            "arrival",
            "flight",
            null,
            "직접 입력 터미널",
            java.time.OffsetDateTime.parse("2026-09-01T09:30:00+09:00"),
            null,
            null);
    var accommodation =
        new com.timingjeju.api.application.accommodation.Accommodation(
            UUID.fromString("24600000-0000-0000-0000-000000000103"),
            null,
            "숙소",
            "숙소",
            base.startDate(),
            base.endDate(),
            java.time.LocalTime.of(15, 0),
            java.time.LocalTime.of(11, 0),
            1,
            base.createdAt(),
            base.updatedAt());
    var trip =
        new TripAggregate(
            base.tripId(),
            base.revision(),
            base.title(),
            base.status(),
            base.startDate(),
            base.endDate(),
            base.timezone(),
            base.userPace(),
            base.transportModes(),
            base.days(),
            null,
            null,
            null,
            base.createdAt(),
            base.updatedAt(),
            new com.timingjeju.api.application.trip.TripTransportEvents(arrival, null),
            List.of(accommodation));
    var mapper = new ObjectMapper();
    var json = mapper.valueToTree(TripAggregateResponse.from(trip));
    assertThat(json.path("transportEvents"))
        .isEqualTo(
            mapper.readTree(
                """
        {"arrival":{"eventType":"arrival","transportType":"flight","terminalPlaceId":null,
        "customTerminalName":"직접 입력 터미널","scheduledAt":"2026-09-01T09:30:00+09:00",
        "transportNumber":null,"note":null},"departure":null}
        """));
    assertThat(json.path("accommodations"))
        .isEqualTo(
            mapper.readTree(
                """
        [{"accommodationId":"24600000-0000-0000-0000-000000000103","placeId":null,
        "customName":"숙소","name":"숙소","checkInDate":"2026-09-01","checkOutDate":"2026-09-02",
        "checkInTime":"15:00","checkOutTime":"11:00","sequenceNo":1}]
        """));
  }

  @Test
  void 미입력_숙소와_교통도_공개_JSON에서_누락하지_않는다() {
    var json = new ObjectMapper().valueToTree(TripAggregateResponse.from(emptyTrip()));
    assertThat(json.has("transportEvents")).isTrue();
    assertThat(json.path("transportEvents").size()).isEqualTo(2);
    assertThat(json.path("transportEvents").path("arrival").isNull()).isTrue();
    assertThat(json.path("transportEvents").path("departure").isNull()).isTrue();
    assertThat(json.path("accommodations").isArray()).isTrue();
    assertThat(json.path("accommodations").size()).isZero();
  }

  private static TripAggregate emptyTrip() {
    UUID id = UUID.fromString("24600000-0000-0000-0000-000000000101");
    var date = LocalDate.of(2026, 9, 1);
    var now = Instant.parse("2026-09-01T00:00:00Z");
    return new TripAggregate(
        id,
        1,
        "복원 여행",
        "draft",
        date,
        date.plusDays(1),
        "Asia/Seoul",
        "normal",
        List.of(new TripTransportMode("public_transit", 1, true)),
        List.of(
            new TripDay(id, 1, date),
            new TripDay(
                UUID.fromString("24600000-0000-0000-0000-000000000102"), 2, date.plusDays(1))),
        null,
        null,
        null,
        now,
        now);
  }
}
