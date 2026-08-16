package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.importing.ImportRunLease;
import java.util.List;
import java.util.Objects;

public record TagoStopCommitCommand(
    ImportRunLease lease,
    long expectedCheckpointVersion,
    TagoCityCode cityCode,
    List<TagoStopWrite> stations,
    List<TagoStopPageLineage> pages) {
  public TagoStopCommitCommand {
    lease = Objects.requireNonNull(lease, "lease는 필수입니다.");
    cityCode = Objects.requireNonNull(cityCode, "cityCode는 필수입니다.");
    stations = List.copyOf(Objects.requireNonNull(stations, "stations는 필수입니다."));
    pages = List.copyOf(Objects.requireNonNull(pages, "pages는 필수입니다."));
  }
}
