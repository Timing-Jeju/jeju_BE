package com.timingjeju.api.application.trip;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 장소 ID와 승인된 스타일 코드만 포함하는 저장 입력이다. */
public record TripPlannerConditions(List<DayAnchor> dayAnchors, List<String> styleCodes) {
  private static final Set<String> STYLES =
      Set.of("restaurant", "cafe", "leisure", "cultural_facility", "relaxed", "trendy", "local");

  public TripPlannerConditions {
    if (dayAnchors == null
        || styleCodes == null
        || dayAnchors.size() > 30
        || styleCodes.size() > STYLES.size()
        || dayAnchors.stream().anyMatch(Objects::isNull)
        || styleCodes.stream().anyMatch(code -> code == null || !STYLES.contains(code))) {
      throw TripException.constraintViolation();
    }
    var days = new HashSet<UUID>();
    if (dayAnchors.stream().anyMatch(anchor -> !days.add(anchor.dayId()))
        || new HashSet<>(styleCodes).size() != styleCodes.size()) {
      throw TripException.constraintViolation();
    }
    dayAnchors =
        dayAnchors.stream()
            .sorted(Comparator.comparing(anchor -> anchor.dayId().toString()))
            .toList();
    styleCodes = styleCodes.stream().sorted().toList();
  }

  public static TripPlannerConditions empty() {
    return new TripPlannerConditions(List.of(), List.of());
  }

  public record DayAnchor(UUID dayId, UUID lodgingPlaceId) {
    public DayAnchor {
      if (dayId == null || lodgingPlaceId == null) throw TripException.constraintViolation();
    }
  }
}
