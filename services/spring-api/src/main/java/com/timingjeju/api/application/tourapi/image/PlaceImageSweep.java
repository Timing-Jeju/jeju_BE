package com.timingjeju.api.application.tourapi.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlaceImageSweep(
    UUID importRunId, int expectedTotal, List<PlaceImagePageLineage> pages) {
  public PlaceImageSweep {
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
    if (expectedTotal < 0 || pages == null || pages.isEmpty()) {
      throw new IllegalArgumentException("image sweep 정보가 올바르지 않습니다.");
    }
    pages = List.copyOf(pages);
    int total = 0;
    for (int index = 0; index < pages.size(); index++) {
      PlaceImagePageLineage page = pages.get(index);
      if (page.pageNo() != index + 1 || !page.lineage().importRunId().equals(importRunId)) {
        throw new IllegalArgumentException("image page 순서 또는 run이 올바르지 않습니다.");
      }
      total += page.rawItemCount();
    }
    if (total != expectedTotal) throw new IllegalArgumentException("image sweep total이 다릅니다.");
  }

  public Instant fetchedAt() {
    return pages.stream()
        .map(PlaceImagePageLineage::fetchedAt)
        .max(Instant::compareTo)
        .orElseThrow();
  }

  public String manifestHash() {
    StringBuilder canonical = new StringBuilder();
    append(canonical, importRunId.toString());
    append(canonical, Integer.toString(expectedTotal));
    for (PlaceImagePageLineage page : pages) {
      append(canonical, Integer.toString(page.pageNo()));
      append(canonical, Integer.toString(page.rawItemCount()));
      append(canonical, page.lineage().snapshotId().toString());
      append(canonical, page.lineage().requestFingerprint());
      append(canonical, page.payloadHash());
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  public UUID sweepId() {
    String hash = manifestHash();
    return UUID.fromString(
        hash.substring(0, 8)
            + '-'
            + hash.substring(8, 12)
            + "-5"
            + hash.substring(13, 16)
            + "-a"
            + hash.substring(17, 20)
            + '-'
            + hash.substring(20, 32));
  }

  private static void append(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }
}
