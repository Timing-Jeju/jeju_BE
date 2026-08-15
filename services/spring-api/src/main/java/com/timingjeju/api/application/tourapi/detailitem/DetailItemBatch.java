package com.timingjeju.api.application.tourapi.detailitem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DetailItemBatch(String contentId, String contentTypeId, List<DetailItem> items) {
  public DetailItemBatch {
    contentId = required(contentId, "contentId");
    contentTypeId = required(contentTypeId, "contentTypeId");
    if (items == null) throw new IllegalArgumentException("items는 필수입니다.");
    items = List.copyOf(items);
    Set<String> keys = new HashSet<>();
    for (DetailItem item : items) {
      String scopedKey = item.itemType() + '\u0000' + item.sourceItemKey();
      if (!keys.add(scopedKey)) {
        throw new IllegalArgumentException("동일 itemType의 sourceItemKey가 중복되었습니다.");
      }
    }
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "는 필수입니다.");
    }
    return value.strip();
  }
}
