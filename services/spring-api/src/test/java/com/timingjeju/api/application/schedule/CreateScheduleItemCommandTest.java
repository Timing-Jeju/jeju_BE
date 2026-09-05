package com.timingjeju.api.application.schedule;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CreateScheduleItemCommandTest {
  private static final UUID VERSION = UUID.fromString("51000000-0000-0000-0000-000000000001");
  private static final UUID PLACE = UUID.fromString("51000000-0000-0000-0000-000000000002");
  private static final UUID ACCOMMODATION = UUID.fromString("51000000-0000-0000-0000-000000000003");
  private static final UUID TRANSPORT = UUID.fromString("51000000-0000-0000-0000-000000000004");

  @ParameterizedTest(name = "{0}")
  @MethodSource("canonicalValidReferences")
  void item_type별_canonical_필수참조와_optional_place를_허용한다(
      String itemType, UUID placeId, UUID accommodationId, UUID transportEventId, String title) {
    assertThatCode(() -> command(itemType, placeId, accommodationId, transportEventId, title))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidReferences")
  void item_type별_필수참조_누락과_금지참조_조합을_거부한다(
      String scenario,
      String itemType,
      UUID placeId,
      UUID accommodationId,
      UUID transportEventId,
      String title) {
    assertThatThrownBy(() -> command(itemType, placeId, accommodationId, transportEventId, title))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_ITEM_INVALID");
  }

  private static Stream<Arguments> canonicalValidReferences() {
    return Stream.of(
        Arguments.of("place_visit", PLACE, null, null, null),
        Arguments.of("meal", null, null, null, "점심"),
        Arguments.of("meal", PLACE, null, null, "점심"),
        Arguments.of("accommodation", null, ACCOMMODATION, null, null),
        Arguments.of("arrival", null, null, TRANSPORT, null),
        Arguments.of("departure", PLACE, null, TRANSPORT, null),
        Arguments.of("free_time", null, null, null, "자유 시간"),
        Arguments.of("custom", PLACE, null, null, "사용자 일정"));
  }

  private static Stream<Arguments> invalidReferences() {
    return Stream.of(
        Arguments.of("place_visit-place-missing", "place_visit", null, null, null, "장소"),
        Arguments.of(
            "place_visit-accommodation-forbidden", "place_visit", PLACE, ACCOMMODATION, null, null),
        Arguments.of("meal-title-missing", "meal", null, null, null, null),
        Arguments.of("meal-transport-forbidden", "meal", null, null, TRANSPORT, "점심"),
        Arguments.of("accommodation-reference-missing", "accommodation", null, null, null, "숙소"),
        Arguments.of(
            "accommodation-transport-forbidden",
            "accommodation",
            PLACE,
            ACCOMMODATION,
            TRANSPORT,
            null),
        Arguments.of("arrival-event-missing", "arrival", null, null, null, "도착"),
        Arguments.of(
            "arrival-accommodation-forbidden", "arrival", PLACE, ACCOMMODATION, TRANSPORT, null),
        Arguments.of("departure-event-missing", "departure", null, null, null, "출발"),
        Arguments.of(
            "departure-accommodation-forbidden",
            "departure",
            PLACE,
            ACCOMMODATION,
            TRANSPORT,
            null),
        Arguments.of("free_time-title-blank", "free_time", null, null, null, " "),
        Arguments.of(
            "free_time-accommodation-forbidden", "free_time", null, ACCOMMODATION, null, "자유 시간"),
        Arguments.of("custom-title-missing", "custom", null, null, null, null),
        Arguments.of("custom-transport-forbidden", "custom", null, null, TRANSPORT, "사용자 일정"));
  }

  private static CreateScheduleItemCommand command(
      String itemType, UUID placeId, UUID accommodationId, UUID transportEventId, String title) {
    return new CreateScheduleItemCommand(
        VERSION,
        1,
        1,
        itemType,
        placeId,
        accommodationId,
        transportEventId,
        title,
        OffsetDateTime.parse("2026-09-01T09:00:00+09:00"),
        60,
        0,
        false,
        null);
  }
}
