package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherImportException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** KMA's village-forecast grid effective from the official 2024-11-28 change notice. */
final class KmaVillageForecastSchedule {
  private static final Set<Integer> EARLY_BASE_HOURS = Set.of(2, 5, 8, 11, 14);
  private static final Set<Integer> LATE_BASE_HOURS = Set.of(17, 20, 23);

  private KmaVillageForecastSchedule() {}

  static List<Slot> slots(LocalDate baseDate, LocalTime baseTime) {
    Objects.requireNonNull(baseDate, "baseDate는 필수입니다.");
    Objects.requireNonNull(baseTime, "baseTime은 필수입니다.");
    if (baseTime.getMinute() != 0 || baseTime.getSecond() != 0) {
      throw KmaWeatherImportException.invalidResponse();
    }
    int extensionDay;
    if (EARLY_BASE_HOURS.contains(baseTime.getHour())) {
      extensionDay = 3;
    } else if (LATE_BASE_HOURS.contains(baseTime.getHour())) {
      extensionDay = 4;
    } else {
      throw KmaWeatherImportException.invalidResponse();
    }
    LocalDateTime extensionStart = baseDate.plusDays(extensionDay).atStartOfDay();
    List<Slot> slots = new ArrayList<>();
    for (LocalDateTime valid = LocalDateTime.of(baseDate, baseTime).plusHours(1);
        valid.isBefore(extensionStart);
        valid = valid.plusHours(1)) {
      slots.add(new Slot(valid, false));
    }
    for (int hour = 0; hour <= 21; hour += 3) {
      slots.add(new Slot(extensionStart.plusHours(hour), true));
    }
    return List.copyOf(slots);
  }

  record Slot(LocalDateTime validTime, boolean qualitative) {}
}
