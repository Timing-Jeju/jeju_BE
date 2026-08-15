package com.timingjeju.api.application.tourapi.detailitem;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

public record DetailItem(
    String itemType,
    String sourceItemKey,
    String title,
    int sequenceNo,
    DetailItemAttributes attributes) {
  private static final Set<String> TYPES = Set.of("info", "course", "room", "menu");

  public DetailItem {
    if (!TYPES.contains(itemType)) {
      throw new IllegalArgumentException("itemType이 올바르지 않습니다.");
    }
    if (sourceItemKey == null || sourceItemKey.isBlank()) {
      throw new IllegalArgumentException("sourceItemKey는 비어 있을 수 없습니다.");
    }
    sourceItemKey = sourceItemKey.strip();
    if (sourceItemKey.getBytes(StandardCharsets.UTF_8).length > 512) {
      throw new IllegalArgumentException("sourceItemKey 크기 제한을 초과했습니다.");
    }
    title = title == null || title.isBlank() ? null : title.strip();
    if (sequenceNo < 1) {
      throw new IllegalArgumentException("sequenceNo는 1 이상이어야 합니다.");
    }
    attributes = Objects.requireNonNull(attributes, "attributes는 필수입니다.");
    if (!attributes.schema().endsWith("." + itemType)) {
      throw new IllegalArgumentException("itemType과 attributes schema가 일치하지 않습니다.");
    }
  }
}
