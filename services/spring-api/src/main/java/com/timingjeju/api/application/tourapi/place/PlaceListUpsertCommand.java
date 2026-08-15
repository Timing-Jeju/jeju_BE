package com.timingjeju.api.application.tourapi.place;

import java.util.List;
import java.util.Objects;

public record PlaceListUpsertCommand(List<PlaceListWrite> writes) {
  public PlaceListUpsertCommand {
    writes = List.copyOf(Objects.requireNonNull(writes, "writes는 필수입니다."));
    if (writes.isEmpty()) {
      throw new IllegalArgumentException("writes는 비어 있을 수 없습니다.");
    }
  }
}
