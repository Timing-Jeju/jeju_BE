package com.timingjeju.api.application.pagination;

import java.util.List;

public record CursorPage<T>(List<T> items, CursorPageInfo page) {

  public CursorPage {
    items = List.copyOf(items);
    if (page == null) {
      throw new IllegalArgumentException("page must not be null");
    }
  }
}
