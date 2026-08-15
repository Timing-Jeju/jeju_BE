package com.timingjeju.api.application.tourapi.detailitem;

import java.util.Objects;

public record DetailItemWrite(DetailItem item, DetailItemPageLineage pageLineage) {
  public DetailItemWrite {
    item = Objects.requireNonNull(item, "item은 필수입니다.");
    pageLineage = Objects.requireNonNull(pageLineage, "pageLineage는 필수입니다.");
  }
}
