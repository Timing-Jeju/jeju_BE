package com.timingjeju.api.application.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("unit")
class TagoArrivalLoadServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID RUN = UUID.fromString("39000000-0000-0000-0000-000000000010");
  private static final UUID OWNER = UUID.fromString("39000000-0000-0000-0000-000000000011");
  private static final UUID SNAPSHOT = UUID.fromString("39000000-0000-0000-0000-000000000012");
  private static final ImportRunLease LEASE = new ImportRunLease(RUN, OWNER, 1);
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");
  private static final byte[] EXACT =
      " {\"response\": {\"body\": {\"arrtime\":1.00}}} \n".getBytes(StandardCharsets.UTF_8);
  private static final TagoArrival ARRIVAL =
      new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4);

  @Test
  void exact_response를_먼저_snapshot하고_같은_bytes를_parse한뒤_lineage와_시간을_commit한다() {
    RecordingSession session = new RecordingSession();
    RecordingSnapshots snapshots = new RecordingSnapshots();
    AtomicReference<byte[]> parsed = new AtomicReference<>();
    AtomicReference<TagoArrivalCommitCommand> committed = new AtomicReference<>();
    TagoArrivalLoadService service =
        service(
            (city, node) -> new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON),
            (format, payload) -> {
              parsed.set(payload);
              return List.of(ARRIVAL);
            },
            session,
            snapshots,
            command -> {
              committed.set(command);
              return new TagoArrivalCommitResult(1);
            });

    TagoArrivalSnapshot result = service.load(KEY);

    assertThat(snapshots.captured.payload()).isSameAs(EXACT);
    assertThat(parsed.get()).isSameAs(EXACT);
    assertThat(committed.get().lease()).isEqualTo(LEASE);
    assertThat(committed.get().snapshot().snapshotId()).isEqualTo(SNAPSHOT);
    assertThat(committed.get().observedAt()).isEqualTo(NOW);
    assertThat(committed.get().expiresAt()).isEqualTo(NOW.plusSeconds(25));
    assertThat(result.arrivals()).containsExactly(ARRIVAL);
    assertThat(result.importRunId()).isEqualTo(RUN);
    assertThat(result.sourceSnapshotId()).isEqualTo(SNAPSHOT);
    assertThat(session.events).containsExactly("start");
  }

  @Test
  void provider_97이나_empty는_확보한_raw를_rejected하고_run을_fail하며_commit하지_않는다() {
    for (TagoArrivalException failure :
        List.of(TagoArrivalException.rateLimited(), TagoArrivalException.emptyResult())) {
      RecordingSession session = new RecordingSession();
      RecordingSnapshots snapshots = new RecordingSnapshots();
      AtomicBoolean committed = new AtomicBoolean();
      TagoArrivalLoadService service =
          service(
              (city, node) -> new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON),
              (format, payload) -> {
                throw failure;
              },
              session,
              snapshots,
              command -> {
                committed.set(true);
                return new TagoArrivalCommitResult(1);
              });

      assertThatThrownBy(() -> service.load(KEY)).isSameAs(failure);
      assertThat(snapshots.events).containsExactly("capture", "reject:" + failure.code());
      assertThat(session.events).containsExactly("start", "fail:" + failure.code());
      assertThat(committed).isFalse();
    }
  }

  @Test
  void timeout은_가짜_raw를_만들지_않고_run만_fail한다() {
    RecordingSession session = new RecordingSession();
    RecordingSnapshots snapshots = new RecordingSnapshots();
    TagoArrivalLoadService service =
        service(
            (city, node) -> {
              throw TagoArrivalException.timeout();
            },
            (format, payload) -> List.of(ARRIVAL),
            session,
            snapshots,
            command -> new TagoArrivalCommitResult(1));

    assertThatThrownBy(() -> service.load(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.TIMEOUT));
    assertThat(snapshots.events).isEmpty();
    assertThat(session.events).containsExactly("start", "fail:TIMEOUT");
  }

  @Test
  void source_fetch동안_Spring_transaction이_없고_claim_lease를_processor에_그대로_전달한다() {
    AtomicBoolean transactionDuringFetch = new AtomicBoolean(true);
    AtomicReference<TagoArrivalFlightLease> processedFlight = new AtomicReference<>();
    TagoArrivalFlightLease flight = new TagoArrivalFlightLease("c".repeat(64), 7, new UUID(39, 70));
    TagoArrivalSnapshot expected =
        new TagoArrivalSnapshot(List.of(ARRIVAL), NOW, NOW.plusSeconds(25), false, RUN, SNAPSHOT);
    TagoArrivalProcessor processor =
        new TagoArrivalProcessor() {
          @Override
          public TagoArrivalProcessResult process(
              TagoArrivalFlightLease observedFlight,
              TagoArrivalCacheKey key,
              TagoArrivalSourceResponse response,
              Instant observedAt,
              Instant expiresAt) {
            processedFlight.set(observedFlight);
            return TagoArrivalProcessResult.success(expected);
          }

          @Override
          public TagoArrivalException.Code recordTransportFailure(
              TagoArrivalFlightLease observedFlight,
              TagoArrivalCacheKey key,
              Instant observedAt,
              TagoArrivalException.Code code) {
            throw new AssertionError("success fixture");
          }
        };
    TagoArrivalLoadService service =
        new TagoArrivalLoadService(
            (city, node) -> {
              transactionDuringFetch.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return new TagoArrivalSourceResponse(EXACT, SnapshotPayloadFormat.JSON);
            },
            processor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            java.time.Duration.ofSeconds(25));

    assertThat(service.load(KEY, flight)).isEqualTo(expected);
    assertThat(transactionDuringFetch).isFalse();
    assertThat(processedFlight.get()).isEqualTo(flight);
  }

  private static TagoArrivalLoadService service(
      TagoArrivalSource source,
      TagoArrivalPayloadParser parser,
      TagoArrivalImportSession session,
      TagoArrivalSnapshotGateway snapshots,
      TagoArrivalCommitter committer) {
    return new TagoArrivalLoadService(
        source,
        parser,
        session,
        snapshots,
        committer,
        Clock.fixed(NOW, ZoneOffset.UTC),
        java.time.Duration.ofSeconds(25));
  }

  private static final class RecordingSession implements TagoArrivalImportSession {
    private final List<String> events = new ArrayList<>();

    @Override
    public ImportRunLease start(TagoArrivalCacheKey key, Instant observedAt) {
      events.add("start");
      return LEASE;
    }

    @Override
    public void fail(ImportRunLease lease, TagoArrivalException.Code code) {
      events.add("fail:" + code);
    }
  }

  private static final class RecordingSnapshots implements TagoArrivalSnapshotGateway {
    private final List<String> events = new ArrayList<>();
    private TagoArrivalSourceResponse captured;

    @Override
    public SavedTagoArrivalSnapshot capture(
        UUID runId,
        TagoArrivalCacheKey key,
        TagoArrivalSourceResponse response,
        Instant observedAt,
        Instant expiresAt) {
      events.add("capture");
      captured = response;
      return new SavedTagoArrivalSnapshot(
          response,
          SNAPSHOT,
          "a".repeat(64),
          observedAt,
          expiresAt,
          false,
          SnapshotStatus.RECEIVED);
    }

    @Override
    public void reject(SavedTagoArrivalSnapshot snapshot, TagoArrivalException.Code code) {
      events.add("reject:" + code);
    }
  }
}
