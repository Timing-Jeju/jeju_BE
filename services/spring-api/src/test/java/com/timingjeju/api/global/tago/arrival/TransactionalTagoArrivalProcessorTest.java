package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitResult;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import com.timingjeju.api.application.tago.arrival.TagoArrivalPayloadParser;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
class TransactionalTagoArrivalProcessorTest {
  private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(new UUID(39, 1), "39", "NODE-39");
  private static final TagoArrivalFlightLease FLIGHT =
      new TagoArrivalFlightLease("a".repeat(64), 3, new UUID(39, 2));
  private static final ImportRunLease RUN = new ImportRunLease(new UUID(39, 3), new UUID(39, 4), 1);
  private static final TagoArrivalSourceResponse RESPONSE =
      new TagoArrivalSourceResponse("{}".getBytes(), SnapshotPayloadFormat.JSON);
  private static final SavedTagoArrivalSnapshot SAVED =
      new SavedTagoArrivalSnapshot(
          RESPONSE,
          new UUID(39, 5),
          "b".repeat(64),
          NOW,
          NOW.plusSeconds(25),
          false,
          SnapshotStatus.RECEIVED);
  private static final TagoArrival ARRIVAL = new TagoArrival("ROUTE", "201", null, null, 60, 1);

  @Test
  void 어떤_DB_write보다_current_fence를_먼저_lock하고_success_CAS를_마지막에_수행한다() {
    List<String> events = new ArrayList<>();
    TagoArrivalFlightStore store = store(events, true);
    TagoArrivalImportSession session = session(events);
    TagoArrivalSnapshotGateway snapshots = snapshots(events);
    TagoArrivalPayloadParser parser =
        (format, payload) -> {
          events.add("parse");
          return List.of(ARRIVAL);
        };
    TagoArrivalCommitter committer =
        command -> {
          events.add("commit");
          return new TagoArrivalCommitResult(1);
        };
    TransactionalTagoArrivalProcessor processor =
        new TransactionalTagoArrivalProcessor(
            parser, session, snapshots, committer, store, Duration.ofSeconds(25));

    processor.process(FLIGHT, KEY, RESPONSE, NOW, NOW.plusSeconds(25));

    assertThat(events).containsExactly("lock", "start", "capture", "parse", "commit", "success");
  }

  @Test
  void 마지막_success_CAS가_0이면_DATA_UNAVAILABLE로_전체_transaction_rollback을_요구한다() {
    TransactionalTagoArrivalProcessor processor =
        new TransactionalTagoArrivalProcessor(
            (format, payload) -> List.of(ARRIVAL),
            session(new ArrayList<>()),
            snapshots(new ArrayList<>()),
            command -> new TagoArrivalCommitResult(1),
            store(new ArrayList<>(), false),
            Duration.ofSeconds(25));

    assertThatThrownBy(() -> processor.process(FLIGHT, KEY, RESPONSE, NOW, NOW.plusSeconds(25)))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE));
  }

  @Test
  void processor와_nested_committer는_REQUIRES_NEW가_아닌_REQUIRED_transaction이다() throws Exception {
    Transactional processor =
        TransactionalTagoArrivalProcessor.class
            .getMethod(
                "process",
                TagoArrivalFlightLease.class,
                TagoArrivalCacheKey.class,
                TagoArrivalSourceResponse.class,
                Instant.class,
                Instant.class)
            .getAnnotation(Transactional.class);
    Transactional committer =
        TransactionalTagoArrivalCommitter.class
            .getMethod(
                "commit",
                com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand.class)
            .getAnnotation(Transactional.class);

    assertThat(processor.propagation()).isEqualTo(Propagation.REQUIRED);
    assertThat(committer.propagation()).isEqualTo(Propagation.REQUIRED);
  }

  private static TagoArrivalFlightStore store(List<String> events, boolean terminalResult) {
    TagoArrivalFlightStore store = mock(TagoArrivalFlightStore.class);
    org.mockito.Mockito.doAnswer(
            ignored -> {
              events.add("lock");
              return null;
            })
        .when(store)
        .lockCurrent(FLIGHT);
    when(store.completeSuccess(FLIGHT, NOW.plusSeconds(25), Duration.ofSeconds(25)))
        .thenAnswer(
            ignored -> {
              events.add("success");
              return terminalResult;
            });
    return store;
  }

  private static TagoArrivalImportSession session(List<String> events) {
    TagoArrivalImportSession session = mock(TagoArrivalImportSession.class);
    when(session.start(KEY, NOW))
        .thenAnswer(
            ignored -> {
              events.add("start");
              return RUN;
            });
    return session;
  }

  private static TagoArrivalSnapshotGateway snapshots(List<String> events) {
    TagoArrivalSnapshotGateway snapshots = mock(TagoArrivalSnapshotGateway.class);
    when(snapshots.capture(RUN.runId(), KEY, RESPONSE, NOW, NOW.plusSeconds(25)))
        .thenAnswer(
            ignored -> {
              events.add("capture");
              return SAVED;
            });
    return snapshots;
  }
}
