package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import com.timingjeju.api.application.tago.arrival.TagoArrivalPayloadParser;
import com.timingjeju.api.application.tago.arrival.TagoArrivalProcessResult;
import com.timingjeju.api.application.tago.arrival.TagoArrivalProcessor;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class TransactionalTagoArrivalProcessor implements TagoArrivalProcessor {
  private final TagoArrivalPayloadParser parser;
  private final TagoArrivalImportSession session;
  private final TagoArrivalSnapshotGateway snapshots;
  private final TagoArrivalCommitter committer;
  private final TagoArrivalFlightStore flights;
  private final Duration replayWindow;

  public TransactionalTagoArrivalProcessor(
      TagoArrivalPayloadParser parser,
      TagoArrivalImportSession session,
      TagoArrivalSnapshotGateway snapshots,
      TagoArrivalCommitter committer,
      TagoArrivalFlightStore flights,
      Duration replayWindow) {
    this.parser = Objects.requireNonNull(parser, "parser는 필수입니다.");
    this.session = Objects.requireNonNull(session, "session은 필수입니다.");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots는 필수입니다.");
    this.committer = Objects.requireNonNull(committer, "committer는 필수입니다.");
    this.flights = Objects.requireNonNull(flights, "flights는 필수입니다.");
    this.replayWindow = Objects.requireNonNull(replayWindow, "replayWindow는 필수입니다.");
  }

  @Transactional
  @Override
  public TagoArrivalProcessResult process(
      TagoArrivalFlightLease flight,
      TagoArrivalCacheKey key,
      TagoArrivalSourceResponse response,
      Instant observedAt,
      Instant expiresAt) {
    flights.lockCurrent(flight);
    ImportRunLease run = session.start(key, observedAt);
    SavedTagoArrivalSnapshot saved =
        snapshots.capture(run.runId(), key, response, observedAt, expiresAt);
    if (saved.status() != SnapshotStatus.RECEIVED) {
      return failParsed(flight, run, saved, TagoArrivalException.Code.INVALID_PROVIDER_RESPONSE);
    }
    List<TagoArrival> arrivals;
    try {
      arrivals = parser.parse(saved.storedResponse().format(), saved.storedResponse().payload());
    } catch (TagoArrivalException failure) {
      return failParsed(flight, run, saved, failure.code());
    }
    committer.commit(
        new TagoArrivalCommitCommand(run, key, arrivals, saved, observedAt, expiresAt));
    if (!flights.completeSuccess(flight, expiresAt, replayWindow)) {
      throw TagoArrivalException.dataUnavailable();
    }
    return TagoArrivalProcessResult.success(
        new TagoArrivalSnapshot(
            arrivals, observedAt, expiresAt, false, run.runId(), saved.snapshotId()));
  }

  @Transactional
  @Override
  public TagoArrivalException.Code recordTransportFailure(
      TagoArrivalFlightLease flight,
      TagoArrivalCacheKey key,
      Instant observedAt,
      TagoArrivalException.Code code) {
    flights.lockCurrent(flight);
    ImportRunLease run = session.start(key, observedAt);
    session.fail(run, code);
    if (!flights.completeFailure(flight, code, replayWindow)) {
      throw TagoArrivalException.dataUnavailable();
    }
    return code;
  }

  private TagoArrivalProcessResult failParsed(
      TagoArrivalFlightLease flight,
      ImportRunLease run,
      SavedTagoArrivalSnapshot saved,
      TagoArrivalException.Code code) {
    snapshots.reject(saved, code);
    session.fail(run, code);
    if (!flights.completeFailure(flight, code, replayWindow)) {
      throw TagoArrivalException.dataUnavailable();
    }
    return TagoArrivalProcessResult.failure(code);
  }
}
