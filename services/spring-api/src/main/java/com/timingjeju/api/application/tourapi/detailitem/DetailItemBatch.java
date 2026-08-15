package com.timingjeju.api.application.tourapi.detailitem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DetailItemBatch(
    String contentId, String contentTypeId, List<DetailItemWrite> writes) {
  public DetailItemBatch {
    contentId = required(contentId, "contentId");
    contentTypeId = required(contentTypeId, "contentTypeId");
    if (writes == null) throw new IllegalArgumentException("writes는 필수입니다.");
    writes = List.copyOf(writes);
    Set<String> keys = new HashSet<>();
    for (DetailItemWrite write : writes) {
      DetailItem item = write.item();
      String scopedKey = item.itemType() + '\u0000' + item.sourceItemKey();
      if (!keys.add(scopedKey)) {
        throw new IllegalArgumentException("동일 itemType의 sourceItemKey가 중복되었습니다.");
      }
    }
  }

  public List<DetailItem> items() {
    return writes.stream().map(DetailItemWrite::item).toList();
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "는 필수입니다.");
    }
    return value.strip();
  }
}
