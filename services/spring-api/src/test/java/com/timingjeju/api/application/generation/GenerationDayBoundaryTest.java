package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.transportevent.TransportEvent;
import com.timingjeju.api.application.trip.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationDayBoundaryTest {
  private static final UUID AIRPORT = UUID.fromString("53000000-0000-0000-0000-000000000099");
  private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

  @Test
  void 첫날과_중간날과_마지막날은_전날_숙소를_이어받는다() {
    var first = resolve(3, 1, 0, "flight");
    var middle = resolve(3, 2, 1, "flight");
    var last = resolve(3, 3, 2, "flight");
    assertThat(first.startPlaceId()).isEqualTo(AIRPORT);
    assertThat(first.endPlaceId()).isEqualTo(lodging(1));
    assertThat(middle.startPlaceId()).isEqualTo(lodging(1));
    assertThat(middle.endPlaceId()).isEqualTo(lodging(2));
    assertThat(last.startPlaceId()).isEqualTo(lodging(2));
    assertThat(last.endPlaceId()).isEqualTo(AIRPORT);
    assertThat(first.startAt().getHour()).isEqualTo(10);
    assertThat(last.endAt().getHour()).isEqualTo(18);
  }

  @Test
  void 당일_여행은_숙소_없이_공항에서_공항으로_활동시간을_제한한다() {
    var result = resolve(1, 1, 0, "flight");
    assertThat(result.startPlaceId()).isEqualTo(AIRPORT);
    assertThat(result.endPlaceId()).isEqualTo(AIRPORT);
    assertThat(result.startAt()).isEqualTo(at(1, 10));
    assertThat(result.endAt()).isEqualTo(at(1, 18));
  }

  @Test
  void 선박과_6일_여행과_앞_Day_건너뛰기는_거부한다() {
    assertThatThrownBy(() -> resolve(3, 1, 0, "ferry"))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThatThrownBy(() -> resolve(6, 1, 0, "flight"))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThatThrownBy(() -> resolve(3, 2, 0, "flight"))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 숙소와_활동시간_누락_및_승인공항_불일치를_거부한다() {
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    days(3),
                    day(1),
                    0,
                    TripPlannerConditions.empty(),
                    events(3, "flight"),
                    AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    List.of(new TripDay(day(1), 1, DATE)),
                    day(1),
                    0,
                    TripPlannerConditions.empty(),
                    events(1, "flight"),
                    AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    var wrong =
        new TripTransportEvents(
            new TransportEvent("arrival", "flight", lodging(1), null, at(1, 10), null, null),
            events(1, "flight").departure());
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    days(1), day(1), 0, TripPlannerConditions.empty(), wrong, AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void UTC_항공시각과_미지정_터미널은_승인공항과_KST로_해석한다() {
    var events =
        new TripTransportEvents(
            new TransportEvent(
                "arrival",
                "flight",
                null,
                null,
                at(1, 10).withOffsetSameInstant(ZoneOffset.UTC),
                null,
                null),
            events(1, "flight").departure());
    var result =
        GenerationDayBoundary.resolve(
            days(1), day(1), 0, TripPlannerConditions.empty(), events, AIRPORT);
    assertThat(result.startAt()).isEqualTo(at(1, 10));
    assertThat(result.startAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(result.startPlaceId()).isEqualTo(AIRPORT);
  }

  @Test
  void 사용자_지정_터미널을_승인공항으로_조용히_치환하지_않는다() {
    var custom =
        new TripTransportEvents(
            new TransportEvent("arrival", "flight", null, "김포공항", at(1, 10), null, null),
            events(1, "flight").departure());
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    days(1), day(1), 0, TripPlannerConditions.empty(), custom, AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 활동시간과_항공시간이_겹치지_않으면_일정을_생성하지_않는다() {
    var early = List.of(new TripDay(day(1), 1, DATE, LocalTime.of(6, 0), LocalTime.of(8, 0)));
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    early, day(1), 0, TripPlannerConditions.empty(), events(1, "flight"), AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    var wrongDate =
        new TripTransportEvents(
            events(1, "flight").arrival(),
            new TransportEvent("departure", "flight", AIRPORT, null, at(2, 18), null, null));
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    days(1), day(1), 0, TripPlannerConditions.empty(), wrongDate, AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 중복_Day와_불연속_날짜와_여행밖_숙소_anchor는_거부한다() {
    var duplicate =
        List.of(
            days(2).get(0),
            new TripDay(day(1), 2, DATE.plusDays(1), LocalTime.of(9, 0), LocalTime.of(21, 0)));
    var discontinuous =
        List.of(
            days(2).get(0),
            new TripDay(day(2), 2, DATE.plusDays(2), LocalTime.of(9, 0), LocalTime.of(21, 0)));
    for (var invalidDays : List.of(duplicate, discontinuous)) {
      assertThatThrownBy(
              () ->
                  GenerationDayBoundary.resolve(
                      invalidDays,
                      day(1),
                      0,
                      TripPlannerConditions.empty(),
                      events(2, "flight"),
                      AIRPORT))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    }
    assertThatThrownBy(
            () ->
                GenerationDayBoundary.resolve(
                    days(1),
                    day(1),
                    0,
                    new TripPlannerConditions(
                        List.of(new TripPlannerConditions.DayAnchor(day(2), lodging(2))),
                        List.of()),
                    events(1, "flight"),
                    AIRPORT))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  private static GenerationDayBoundary resolve(
      int count, int target, int completed, String transport) {
    var anchors = new ArrayList<TripPlannerConditions.DayAnchor>();
    for (int n = 1; n < count; n++)
      anchors.add(new TripPlannerConditions.DayAnchor(day(n), lodging(n)));
    return GenerationDayBoundary.resolve(
        days(count),
        day(target),
        completed,
        new TripPlannerConditions(anchors, List.of()),
        events(count, transport),
        AIRPORT);
  }

  private static List<TripDay> days(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(
            n ->
                new TripDay(
                    day(n), n, DATE.plusDays(n - 1), LocalTime.of(9, 0), LocalTime.of(21, 0)))
        .toList();
  }

  private static TripTransportEvents events(int count, String type) {
    return new TripTransportEvents(
        new TransportEvent("arrival", type, AIRPORT, null, at(1, 10), null, null),
        new TransportEvent("departure", type, AIRPORT, null, at(count, 18), null, null));
  }

  private static OffsetDateTime at(int day, int hour) {
    return DATE.plusDays(day - 1).atTime(hour, 0).atOffset(ZoneOffset.ofHours(9));
  }

  private static UUID day(int n) {
    return new UUID(53, n);
  }

  private static UUID lodging(int n) {
    return new UUID(54, n);
  }
}
