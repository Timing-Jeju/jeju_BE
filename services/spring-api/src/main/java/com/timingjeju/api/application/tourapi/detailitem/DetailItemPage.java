package com.timingjeju.api.application.tourapi.detailitem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DetailItemPage(
    String contentId,
    String contentTypeId,
    int pageNo,
    int numOfRows,
    int totalCount,
    int rawItemCount,
    List<DetailItem> items) {

  public DetailItemPage {
    contentId = required(contentId);
    contentTypeId = required(contentTypeId);
    if (pageNo < 1
        || numOfRows < 1
        || totalCount < 0
        || rawItemCount < 0
        || items == null
        || rawItemCount != items.size()
        || rawItemCount > numOfRows) {
      throw DetailItemImportException.invalidResponse();
    }
    items = List.copyOf(items);
    Set<String> keys = new HashSet<>();
    for (DetailItem item : items) {
      if (!keys.add(item.itemType() + '\u0000' + item.sourceItemKey())) {
        throw DetailItemImportException.invalidResponse();
      }
    }
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) {
      throw DetailItemImportException.invalidResponse();
    }
    return value.strip();
  }
}
