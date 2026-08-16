package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class TagoArrivalLoadService implements TagoArrivalLoader {
  private final TagoArrivalSource source;
  private final TagoArrivalPayloadParser parser;
  private final TagoArrivalImportSession session;
  private final TagoArrivalSnapshotGateway snapshots;
  private final TagoArrivalCommitter committer;
  private final Clock clock;
  private final Duration ttl;

  public TagoArrivalLoadService(
      TagoArrivalSource source,
      TagoArrivalPayloadParser parser,
      TagoArrivalImportSession session,
      TagoArrivalSnapshotGateway snapshots,
      TagoArrivalCommitter committer,
      Clock clock,
      Duration ttl) {
    this.source = Objects.requireNonNull(source, "source는 필수입니다.");
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.session = Objects.requireNonNull(session, "session은 필수입니다.");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.ttl = Objects.requireNonNull(ttl, "ttl은 필수입니다.");
    if (ttl.compareTo(Duration.ofSeconds(20)) < 0 || ttl.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("arrival TTL은 20~30초여야 합니다.");
    }
  }

  @Override
  public TagoArrivalSnapshot load(TagoArrivalCacheKey key) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Instant observedAt = clock.instant();
    Instant expiresAt = observedAt.plus(ttl);
    ImportRunLease lease = session.start(key, observedAt);
    SavedTagoArrivalSnapshot saved = null;
    try {
      TagoArrivalSourceResponse response = source.fetch(key.cityCode(), key.nodeId());
      saved = snapshots.capture(lease.runId(), key, response, observedAt, expiresAt);
      if (saved.status() != SnapshotStatus.RECEIVED) {
        throw TagoArrivalException.invalidResponse();
      }
      List<TagoArrival> arrivals;
      try {
        arrivals = parser.parse(saved.storedResponse().format(), saved.storedResponse().payload());
      } catch (TagoArrivalException failure) {
        snapshots.reject(saved, failure.code());
        session.fail(lease, failure.code());
        throw failure;
      }
      committer.commit(
          new TagoArrivalCommitCommand(lease, key, arrivals, saved, observedAt, expiresAt));
      return new TagoArrivalSnapshot(
          arrivals, observedAt, expiresAt, false, lease.runId(), saved.snapshotId());
    } catch (TagoArrivalException failure) {
      if (saved == null) session.fail(lease, failure.code());
      throw failure;
    } catch (RuntimeException failure) {
      session.fail(lease, TagoArrivalException.Code.PROVIDER_UNAVAILABLE);
      throw TagoArrivalException.providerUnavailable();
    }
  }
}
