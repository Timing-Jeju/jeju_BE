package com.timingjeju.api.application.tourapi.reference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ReferenceCodeUpsertCommand(
    List<ReferenceCode> codes,
    LocalDate validFrom,
    LocalDate validTo,
    Instant seenAt,
    ReferenceCodeLineage lineage) {
  public ReferenceCodeUpsertCommand {
    codes = List.copyOf(Objects.requireNonNull(codes, "codes는 필수입니다."));
    if (codes.isEmpty()) {
      throw new IllegalArgumentException("codes는 비어 있을 수 없습니다.");
    }
    validFrom = Objects.requireNonNull(validFrom, "validFrom은 필수입니다.");
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("validTo가 validFrom보다 빠릅니다.");
    }
    seenAt = Objects.requireNonNull(seenAt, "seenAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
