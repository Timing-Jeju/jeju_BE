package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class KmaVillageForecastScheduleTest {
  private static final LocalDate BASE_DATE = LocalDate.of(2026, 8, 16);

  @Test
  void fixtureIdentifiesTheOfficial20241128NoticeAndAttachment() throws Exception {
    try (var input =
        getClass()
            .getClassLoader()
            .getResourceAsStream("fixtures/kma/village-forecast-grid-20241128.json")) {
      assertThat(input).isNotNull();
      var fixture = new tools.jackson.databind.ObjectMapper().readTree(input.readAllBytes());
      assertThat(fixture.path("effectiveDate").asString()).isEqualTo("2024-11-28");
      assertThat(fixture.path("authoritativeNotice").asString())
          .isEqualTo("https://apihub.kma.go.kr/notice.do?seqNotice=33");
      assertThat(fixture.path("authoritativeAttachment").asString())
          .startsWith("https://apihub.kma.go.kr/getAttachFile.do?");
    }
  }

  @ParameterizedTest(name = "official 2024-11-28 base {0} has {1} slots and extended day +{2}")
  @CsvSource({
    "02:00,77,3",
    "05:00,74,3",
    "08:00,71,3",
    "11:00,68,3",
    "14:00,65,3",
    "17:00,86,4",
    "20:00,83,4",
    "23:00,80,4"
  })
  void buildsEveryOfficialBaseScheduleWithHourlyThenThreeHourlyExtendedGrid(
      LocalTime baseTime, int expectedSize, int extendedDayOffset) {
    List<KmaVillageForecastSchedule.Slot> slots =
        KmaVillageForecastSchedule.slots(BASE_DATE, baseTime);
    LocalDateTime extendedStart = BASE_DATE.plusDays(extendedDayOffset).atStartOfDay();

    assertThat(slots).hasSize(expectedSize);
    assertThat(slots.getFirst().validTime())
        .isEqualTo(LocalDateTime.of(BASE_DATE, baseTime).plusHours(1));
    assertThat(slots.stream().filter(KmaVillageForecastSchedule.Slot::qualitative).toList())
        .extracting(KmaVillageForecastSchedule.Slot::validTime)
        .containsExactly(
            extendedStart,
            extendedStart.plusHours(3),
            extendedStart.plusHours(6),
            extendedStart.plusHours(9),
            extendedStart.plusHours(12),
            extendedStart.plusHours(15),
            extendedStart.plusHours(18),
            extendedStart.plusHours(21));
    assertThat(slots.stream().filter(slot -> !slot.qualitative()).toList())
        .allSatisfy(slot -> assertThat(slot.validTime()).isBefore(extendedStart));
  }
}
