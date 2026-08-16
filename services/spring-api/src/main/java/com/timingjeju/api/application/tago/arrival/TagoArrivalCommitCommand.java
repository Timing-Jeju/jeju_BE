package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunLease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TagoArrivalCommitCommand(
    ImportRunLease lease,
    TagoArrivalCacheKey key,
    List<TagoArrival> arrivals,
    SavedTagoArrivalSnapshot snapshot,
    Instant observedAt,
    Instant expiresAt) {

  public TagoArrivalCommitCommand {
    Objects.requireNonNull(lease, "lease는 필수입니다.");
    Objects.requireNonNull(key, "key는 필수입니다.");
    arrivals = List.copyOf(Objects.requireNonNull(arrivals, "arrivals는 필수입니다."));
    if (arrivals.isEmpty()) throw TagoArrivalException.emptyResult();
    Objects.requireNonNull(snapshot, "snapshot은 필수입니다.");
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
  }
}
