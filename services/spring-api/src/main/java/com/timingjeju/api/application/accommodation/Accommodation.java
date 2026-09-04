package com.timingjeju.api.application.accommodation;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

public record Accommodation(
    UUID accommodationId,
    UUID placeId,
    String customName,
    String name,
    LocalDate checkInDate,
    LocalDate checkOutDate,
    LocalTime checkInTime,
    LocalTime checkOutTime,
    int sequenceNo,
    Instant createdAt,
    Instant updatedAt) {
  public Accommodation {
    Objects.requireNonNull(accommodationId);
    Objects.requireNonNull(name);
    Objects.requireNonNull(checkInDate);
    Objects.requireNonNull(checkOutDate);
    Objects.requireNonNull(checkInTime);
    Objects.requireNonNull(checkOutTime);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(updatedAt);
    if ((placeId == null) == (customName == null) || sequenceNo < 1) {
      throw new IllegalArgumentException("숙소 identity와 sequence가 올바르지 않습니다.");
    }
    if (name.isBlank()
        || name.charAt(0) <= 0x20
        || name.charAt(name.length() - 1) <= 0x20
        || name.codePointCount(0, name.length()) > 100
        || !Normalizer.isNormalized(name, Normalizer.Form.NFC)
        || name.chars().anyMatch(Character::isISOControl)) {
      throw AccommodationException.of("ACCOMMODATION_DATA_UNAVAILABLE");
    }
  }
}
