package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.application.staypolicy.*;
import com.timingjeju.api.application.transportevent.TransportEvent;
import com.timingjeju.api.application.trip.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationTripInputTest {
  private static final UUID TRIP = new UUID(53, 1),
      DAY = new UUID(53, 2),
      AIRPORT = new UUID(53, 3),
      PLACE = new UUID(53, 4);
  private static final LocalDate DATE = LocalDate.of(2026, 10, 1);

  @Test
  void 저장_조건은_원문_없이_경계와_검증된_체류시간으로_고정한다() {
    var input =
        GenerationTripInput.capture(
            trip(null),
            DAY,
            0,
            AIRPORT,
            Map.of(
                PLACE,
                new RecommendedStay(
                    75,
                    RecommendedStaySource.CATEGORY_DEFAULT,
                    "stay-v1",
                    Instant.EPOCH,
                    Instant.EPOCH)));
    assertThat(input.tripRevision()).isEqualTo(7);
    assertThat(input.baseScheduleVersionId()).isNull();
    assertThat(input.boundary().startPlaceId()).isEqualTo(AIRPORT);
    assertThat(input.places().getFirst().stayMinutes()).isEqualTo(75);
    assertThat(input.places().getFirst().stayPolicyVersion()).isEqualTo("stay-v1");
    assertThat(input.transportModes()).containsExactly("bus", "taxi", "walk");
    assertThat(input.preferredCategories()).containsExactly("cafe", "restaurant");
    assertThat(input.relaxedPace()).isTrue();
    String json = JsonMapper.builder().build().writeValueAsString(input);
    assertThat(json).doesNotContain("비공개 여행 제목", "비공개 항공 메모", "비공개 편명", "trendy", "local");
  }

  @Test
  void 명시_체류시간은_추천보다_우선하며_출처를_혼합하지_않는다() {
    var input = GenerationTripInput.capture(trip(90), DAY, 0, AIRPORT, Map.of());
    assertThat(input.places().getFirst().stayMinutes()).isEqualTo(90);
    assertThat(input.places().getFirst().staySource()).isEqualTo("user_requested");
    assertThat(input.places().getFirst().stayPolicyVersion()).isNull();
  }

  @Test
  void 체류시간이_없거나_검증된_추천이_아니면_60분으로_대체하지_않는다() {
    for (var policies :
        List.of(
            Map.<UUID, RecommendedStay>of(),
            Map.of(PLACE, RecommendedStay.unavailable()),
            Map.of(
                PLACE,
                new RecommendedStay(
                    60, RecommendedStaySource.CATEGORY_DEFAULT, null, null, null)))) {
      assertThatThrownBy(() -> GenerationTripInput.capture(trip(null), DAY, 0, AIRPORT, policies))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    }
  }

  private static TripAggregate trip(Integer stay) {
    return trip(
        List.of(new TripPlacePreference(PLACE, "must_visit", 1, 100, stay)),
        List.of(
            new TripTransportMode("walk", 3, false),
            new TripTransportMode("public_transit", 1, true),
            new TripTransportMode("taxi", 2, false)));
  }

  @Test
  void 복원용_생성자도_Day와_경계_불일치를_거부한다() {
    var input = GenerationTripInput.capture(trip(90), DAY, 0, AIRPORT, Map.of());
    var old = input.boundary();
    var wrong =
        new GenerationDayBoundary(
            old.dayId(), 2, old.startPlaceId(), old.endPlaceId(), old.startAt(), old.endAt());
    assertThatThrownBy(
            () ->
                new GenerationTripInput(
                    input.tripId(),
                    input.tripRevision(),
                    null,
                    wrong,
                    input.airportPlaceId(),
                    input.days(),
                    input.dayAnchors(),
                    input.savedPreferences(),
                    input.transportModes(),
                    input.preferredCategories(),
                    input.relaxedPace(),
                    input.places()))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 불변_snapshot은_작업과_소유자를_hash에_묶고_복원시_검증한다() {
    var mapper = JsonMapper.builder().build();
    var input = GenerationTripInput.capture(trip(90), DAY, 0, AIRPORT, Map.of());
    var run = new UUID(53, 10);
    var owner = new UUID(53, 11);
    var snapshot = GenerationTripSnapshot.create(run, owner, input, mapper);
    assertThat(snapshot.inputHash()).matches("[0-9a-f]{64}");
    assertThat(
            GenerationTripSnapshot.restore(
                    run, owner, snapshot.canonicalInput(), snapshot.inputHash(), mapper)
                .input())
        .isEqualTo(input);
    assertThatThrownBy(
            () ->
                GenerationTripSnapshot.restore(
                    new UUID(53, 12),
                    owner,
                    snapshot.canonicalInput(),
                    snapshot.inputHash(),
                    mapper))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThatThrownBy(
            () ->
                GenerationTripSnapshot.restore(
                    run,
                    owner,
                    snapshot.canonicalInput().replace("90", "91"),
                    snapshot.inputHash(),
                    mapper))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    assertThatThrownBy(
            () ->
                GenerationTripSnapshot.restore(
                    run,
                    owner,
                    snapshot
                        .canonicalInput()
                        .replace(
                            "\"tripRevision\":7", "\"tripRevision\":7,\"originalText\":\"원문\""),
                    snapshot.inputHash(),
                    mapper))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 제외_장소에는_체류시간을_요구하지_않고_선택_없는_Day는_거부한다() {
    var avoid = new TripPlacePreference(new UUID(53, 5), "avoid", null, 0, null);
    var required = new TripPlacePreference(PLACE, "must_visit", null, 100, 45);
    var modes = List.of(new TripTransportMode("walk", 1, true));
    var input =
        GenerationTripInput.capture(
            trip(List.of(required, avoid), modes), DAY, 0, AIRPORT, Map.of());
    assertThat(input.places().getLast().stayMinutes()).isNull();
    assertThat(input.places().getLast().staySource()).isNull();
    assertThatThrownBy(
            () ->
                GenerationTripInput.capture(trip(List.of(avoid), modes), DAY, 0, AIRPORT, Map.of()))
        .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  @Test
  void 미지원_이동수단과_중복_모드를_다른_수단으로_바꾸지_않는다() {
    var preferences = List.of(new TripPlacePreference(PLACE, "preferred", 1, 50, 30));
    for (var modes :
        List.of(
            List.of(new TripTransportMode("rental_car", 1, true)),
            List.of(
                new TripTransportMode("taxi", 1, true), new TripTransportMode("taxi", 2, false)),
            List.<TripTransportMode>of())) {
      assertThatThrownBy(
              () ->
                  GenerationTripInput.capture(trip(preferences, modes), DAY, 0, AIRPORT, Map.of()))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    }
  }

  @Test
  void 범위밖_체류시간과_장소중복_및_최대필수장소_초과를_거부한다() {
    var modes = List.of(new TripTransportMode("taxi", 1, true));
    var tooMany =
        java.util.stream.IntStream.range(0, 11)
            .mapToObj(n -> new TripPlacePreference(new UUID(60, n), "must_visit", 1, 100, 20))
            .toList();
    for (var preferences :
        List.of(
            tooMany,
            List.of(new TripPlacePreference(PLACE, "preferred", 1, 50, 0)),
            List.of(new TripPlacePreference(PLACE, "preferred", 1, 50, 1441)),
            List.of(
                new TripPlacePreference(PLACE, "preferred", 1, 50, 30),
                new TripPlacePreference(PLACE, "avoid", 1, 0, null)))) {
      assertThatThrownBy(
              () ->
                  GenerationTripInput.capture(trip(preferences, modes), DAY, 0, AIRPORT, Map.of()))
          .hasMessage("GENERATION_INPUT_CONSTRAINT_VIOLATION");
    }
  }

  private static TripAggregate trip(
      List<TripPlacePreference> preferences, List<TripTransportMode> modes) {
    var arrival = DATE.atTime(10, 0).atOffset(ZoneOffset.ofHours(9));
    return new TripAggregate(
        TRIP,
        7,
        "비공개 여행 제목",
        "draft",
        DATE,
        DATE,
        "Asia/Seoul",
        "normal",
        modes,
        List.of(new TripDay(DAY, 1, DATE, LocalTime.of(9, 0), LocalTime.of(21, 0))),
        null,
        null,
        null,
        Instant.EPOCH,
        Instant.EPOCH,
        new TripTransportEvents(
            new TransportEvent("arrival", "flight", AIRPORT, null, arrival, "비공개 편명", "비공개 항공 메모"),
            new TransportEvent(
                "departure", "flight", AIRPORT, null, arrival.plusHours(8), null, null)),
        List.of(),
        preferences,
        new TripPlannerConditions(
            List.of(), List.of("restaurant", "cafe", "relaxed", "trendy", "local")));
  }
}
