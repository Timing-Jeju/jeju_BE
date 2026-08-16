package com.timingjeju.api.application.tourapi.detailitem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DetailItemSweep(
    UUID importRunId, int expectedTotal, List<DetailItemPageLineage> pages) {
  public DetailItemSweep {
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
    if (expectedTotal < 0 || pages == null || pages.isEmpty()) {
      throw new IllegalArgumentException("complete sweep 정보가 올바르지 않습니다.");
    }
    pages = List.copyOf(pages);
    int rawTotal = 0;
    for (int index = 0; index < pages.size(); index++) {
      DetailItemPageLineage page = pages.get(index);
      if (page.pageNo() != index + 1 || !page.lineage().importRunId().equals(importRunId)) {
        throw new IllegalArgumentException("page lineage 순서 또는 run이 올바르지 않습니다.");
      }
      rawTotal += page.rawItemCount();
    }
    if (rawTotal != expectedTotal) {
      throw new IllegalArgumentException("complete sweep total이 일치하지 않습니다.");
    }
  }

  public Instant fetchedAt() {
    return pages.stream()
        .map(DetailItemPageLineage::fetchedAt)
        .max(Instant::compareTo)
        .orElseThrow();
  }

  public String manifestHash() {
    StringBuilder canonical = new StringBuilder();
    append(canonical, importRunId.toString());
    append(canonical, Integer.toString(expectedTotal));
    for (DetailItemPageLineage page : pages) {
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
