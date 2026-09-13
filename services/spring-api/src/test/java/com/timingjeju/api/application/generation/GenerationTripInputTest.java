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
  void 서버_추천_체류시간은_사용자_입력으로_위장하지_않고_정책_출처를_전달한다() {
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
    var bindings =
        new GenerationPlaceBindings(
            List.of(
                new GenerationPlaceBindings.Place(AIRPORT, "1", "공항"),
                new GenerationPlaceBindings.Place(PLACE, "2", "공식 장소")));
    assertThat(GenerationMcpDayConditions.from(input, bindings))
        .containsEntry(
            "place_duration_preferences",
            List.of(
                Map.of(
                    "place_id",
                    "tourapi.place:2",
                    "requested_stay_minutes",
                    75,
                    "source",
                    "category_default",
                    "policy_version",
                    "stay-v1",
                    "policy_effective_at",
                    "1970-01-01T00:00:00Z")));
  }

  @Test
  void 선택과_회피를_분리하고_택시만_허용하면_다른_수단을_추가하지_않는다() {
    var avoid = new UUID(53, 5);
    var input =
        GenerationTripInput.capture(
            trip(
                List.of(
                    new TripPlacePreference(PLACE, "preferred", 1, 50, 30),
                    new TripPlacePreference(avoid, "avoid", 1, 0, null)),
                List.of(new TripTransportMode("taxi", 1, true))),
            DAY,
            0,
            AIRPORT,
            Map.of());
    var bindings =
        new GenerationPlaceBindings(
            List.of(
                new GenerationPlaceBindings.Place(AIRPORT, "1", "공항"),
                new GenerationPlaceBindings.Place(PLACE, "2", "선택 장소"),
                new GenerationPlaceBindings.Place(avoid, "3", "제외 장소")));
    var request = GenerationMcpDayConditions.from(input, bindings);
    assertThat(request)
        .containsEntry("required_places", List.of())
        .containsEntry("preferred_places", List.of(Map.of("place_id", "tourapi.place:2")))
        .containsEntry("excluded_places", List.of(Map.of("place_id", "tourapi.place:3")))
        .containsEntry(
            "transport",
            Map.of(
                "allowed_modes",
                List.of("taxi"),
                "preferred_mode",
                "taxi",
                "fallback_order",
                List.of()))
        .containsEntry(
            "place_duration_preferences",
            List.of(Map.of("place_id", "tourapi.place:2", "requested_stay_minutes", 30)));
    assertThatThrownBy(
            () -> GenerationMcpDayConditions.from(input, new GenerationPlaceBindings(List.of())))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
  }

  @Test
  void 첫날과_마지막날은_공항을_숙소로_오인하지_않고_명시된_Day_경계를_보존한다() {
    var day2 = new UUID(53, 6);
    var lodging = new UUID(53, 7);
    var days =
        List.of(
            new TripDay(DAY, 1, DATE, LocalTime.of(9, 0), LocalTime.of(21, 0)),
            new TripDay(day2, 2, DATE.plusDays(1), LocalTime.of(9, 0), LocalTime.of(21, 0)));
    var bindings =
        new GenerationPlaceBindings(
            List.of(
                new GenerationPlaceBindings.Place(AIRPORT, "1", "공항"),
                new GenerationPlaceBindings.Place(PLACE, "2", "방문 장소"),
                new GenerationPlaceBindings.Place(lodging, "3", "공식 숙소")));
    for (int n : List.of(1, 2)) {
      var start = DATE.plusDays(n - 1).atTime(10, 0).atOffset(ZoneOffset.ofHours(9));
      var input =
          new GenerationTripInput(
              TRIP,
              7,
              n == 1 ? null : new UUID(53, 8),
              new GenerationDayBoundary(
                  n == 1 ? DAY : day2,
                  n,
                  n == 1 ? AIRPORT : lodging,
                  n == 1 ? lodging : AIRPORT,
                  start,
                  start.plusHours(8)),
              AIRPORT,
              days,
              List.of(new TripPlannerConditions.DayAnchor(DAY, lodging)),
              List.of(new TripPlacePreference(PLACE, "must_visit", null, 100, 90)),
              List.of("walk"),
              List.of(),
              false,
              List.of(
                  new GenerationTripInput.PlaceInput(
                      PLACE, "must_visit", 100, 90, "user_requested", null, null)));
      var request = GenerationMcpDayConditions.from(input, bindings);
      assertThat(request)
          .containsEntry("accommodation", Map.of("place_id", "tourapi.place:3", "name", "공식 숙소"))
          .doesNotContainKey("rest");
      assertThat(request.get("day_boundary"))
          .isEqualTo(
              Map.of(
                  "start_place", bindings.reference(input.boundary().startPlaceId()),
                  "end_place", bindings.reference(input.boundary().endPlaceId())));
    }
  }

  @Test
  void MCP_조건은_저장된_시간과_체류시간을_유지하고_공개_fact_ID만_전달한다() throws Exception {
    var input = GenerationTripInput.capture(trip(90), DAY, 0, AIRPORT, Map.of());
    var bindings =
        new GenerationPlaceBindings(
            List.of(
                new GenerationPlaceBindings.Place(AIRPORT, "126471", "제주국제공항"),
                new GenerationPlaceBindings.Place(PLACE, "126472", "공식 방문 장소")));
    var request = GenerationMcpDayConditions.from(input, bindings);
    assertThat(request)
        .containsEntry("schema_version", "0.7.0")
        .containsEntry("trip_date", "2026-10-01")
        .containsEntry("required_places", List.of(Map.of("place_id", "tourapi.place:126472")))
        .containsEntry(
            "place_duration_preferences",
            List.of(Map.of("place_id", "tourapi.place:126472", "requested_stay_minutes", 90)))
        .containsEntry(
            "transport",
            Map.of(
                "allowed_modes",
                List.of("bus", "taxi", "walk"),
                "preferred_mode",
                "bus",
                "fallback_order",
                List.of("taxi", "walk")))
        .containsEntry("rest", Map.of("pace", "relaxed"));
    var json = JsonMapper.builder().build().writeValueAsString(request);
    try (var fixture =
        getClass().getResourceAsStream("/mcp/generation-day-conditions-v07.input.json")) {
      assertThat(fixture).isNotNull();
      var mapper = JsonMapper.builder().build();
      assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(fixture));
    }
    assertThat(json)
        .doesNotContain(
            TRIP.toString(),
            DAY.toString(),
            PLACE.toString(),
            AIRPORT.toString(),
            "비공개",
            "original_text",
            "coordinates",
            "trendy",
            "local");
    assertThat(request.get("activity_window"))
        .isEqualTo(
            Map.of("start_at", "2026-10-01T10:00:00+09:00", "end_at", "2026-10-01T18:00:00+09:00"));
  }

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
