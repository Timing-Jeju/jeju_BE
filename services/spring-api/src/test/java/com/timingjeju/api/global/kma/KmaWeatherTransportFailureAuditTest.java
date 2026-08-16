package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportCheckpointService;
import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.importing.ImportRunLifecycleService;
import com.timingjeju.api.application.importing.ImportRunStartResult;
import com.timingjeju.api.application.kma.KmaWeatherBaseTimeResolver;
import com.timingjeju.api.application.kma.KmaWeatherCommitter;
import com.timingjeju.api.application.kma.KmaWeatherImportCommand;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherImportService;
import com.timingjeju.api.application.kma.KmaWeatherParser;
import com.timingjeju.api.application.snapshot.SnapshotIdentityGenerator;
import com.timingjeju.api.application.snapshot.SnapshotMutationOutcome;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotRedactionResult;
import com.timingjeju.api.application.snapshot.SnapshotRedactor;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStateMutation;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStore;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.StoredSnapshot;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.snapshot.DeterministicSnapshotRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class KmaWeatherTransportFailureAuditTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-15T15:45:00Z"), ZoneOffset.UTC);
  private static final byte[] PAGE_ONE = villageEnvelope(1).getBytes(StandardCharsets.UTF_8);
  private static final byte[] PAGE_TWO = villageEnvelope(2).getBytes(StandardCharsets.UTF_8);

  @ParameterizedTest(
      name = "transport failure at {0} preserves all prior responses as rejected audit")
  @ValueSource(strings = {"page2", "version"})
  void realClientThroughServiceAndSnapshotStoreRejectsAccumulatedExactResponsesWithoutCommit(
      String failurePoint) {
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              if ("page2".equals(failurePoint)
                  && "2".equals(request.queryParameters().get("pageNo"))) {
                throw new IllegalStateException("page2 transport failed");
              }
              if ("version".equals(failurePoint)
                  && request.operation() == ExternalApiOperation.KMA_FORECAST_VERSION) {
                throw new IllegalStateException("version transport failed");
              }
              return "2".equals(request.queryParameters().get("pageNo")) ? PAGE_TWO : PAGE_ONE;
            });
    RecordingStore store = new RecordingStore();
    RecordingRedactor redactor = new RecordingRedactor();
    SnapshotIdentityGenerator ids = UUID::randomUUID;
    SnapshotStoreService snapshotStore = new SnapshotStoreService(store, redactor, CLOCK, ids);
    SnapshottingKmaWeatherGateway gateway = new SnapshottingKmaWeatherGateway(snapshotStore, CLOCK);
    ImportCheckpointService checkpoints = mock(ImportCheckpointService.class);
    ImportRunLifecycleService runs = mock(ImportRunLifecycleService.class);
    KmaWeatherParser parser = mock(KmaWeatherParser.class);
    KmaWeatherCommitter committer = mock(KmaWeatherCommitter.class);
    ImportRunLease lease = new ImportRunLease(UUID.randomUUID(), UUID.randomUUID(), 1L);
    when(checkpoints.find(any())).thenReturn(Optional.empty());
    when(runs.start(any())).thenReturn(new ImportRunStartResult(lease, false));
    KmaWeatherImportService service =
        new KmaWeatherImportService(
            client,
            gateway,
            parser,
            checkpoints,
            runs,
            committer,
            new KmaWeatherBaseTimeResolver(CLOCK));

    assertThatThrownBy(
            () ->
                service.importVillageForecast(
                    new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "transport-audit")))
        .isInstanceOf(KmaWeatherImportException.class);

    List<String> expectedKeys =
        "page2".equals(failurePoint)
            ? List.of("getVilageFcst:1", "manifest", "getVilageFcst:1", "manifest")
            : List.of(
                "getVilageFcst:1",
                "getVilageFcst:2",
                "manifest",
                "getVilageFcst:1",
                "getVilageFcst:2",
                "manifest");
    assertThat(store.saved)
        .extracting(StoredSnapshot::pageKey)
        .containsExactlyElementsOf(expectedKeys);
    assertThat(store.saved.stream().filter(value -> !"manifest".equals(value.pageKey())))
        .allSatisfy(
            value -> {
              byte[] expected = value.pageKey().endsWith(":1") ? PAGE_ONE : PAGE_TWO;
              assertThat(value.payloadHash()).isEqualTo(sha256(expected));
            });
    List<byte[]> providerPayloads = new ArrayList<>();
    for (int index = 0; index < store.saved.size(); index++) {
      if (!"manifest".equals(store.saved.get(index).pageKey())) {
        providerPayloads.add(redactor.payloads.get(index));
      }
    }
    assertThat(providerPayloads)
        .containsExactlyElementsOf(
            "page2".equals(failurePoint)
                ? List.of(PAGE_ONE, PAGE_ONE)
                : List.of(PAGE_ONE, PAGE_TWO, PAGE_ONE, PAGE_TWO));
    assertThat(store.transitions)
        .hasSameSizeAs(store.saved)
        .allSatisfy(value -> assertThat(value.status()).isEqualTo(SnapshotStatus.REJECTED));
    verify(parser, never()).parse(any(), any());
    verify(committer, never()).commit(any());
  }

  private static String villageEnvelope(int page) {
    return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"dataType\":\"JSON\","
        + "\"pageNo\":"
        + page
        + ",\"numOfRows\":1000,\"totalCount\":1001,\"items\":{\"item\":[]}}}}";
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static final class RecordingStore implements SnapshotStore {
    private final List<StoredSnapshot> saved = new ArrayList<>();
    private final List<SnapshotStateMutation> transitions = new ArrayList<>();

    @Override
    public SnapshotSaveResult save(StoredSnapshot snapshot) {
      saved.add(snapshot);
      return snapshot.result(false);
    }

    @Override
    public SnapshotMutationOutcome transition(SnapshotStateMutation mutation) {
      transitions.add(mutation);
      return SnapshotMutationOutcome.UPDATED;
    }
  }

  private static final class RecordingRedactor implements SnapshotRedactor {
    private final SnapshotRedactor delegate = new DeterministicSnapshotRedactor(new ObjectMapper());
    private final List<byte[]> payloads = new ArrayList<>();

    @Override
    public SnapshotRedactionResult redact(
        SnapshotPayloadFormat format,
        String charset,
        byte[] decompressedPayload,
        Map<String, Object> requestMetadata) {
      payloads.add(decompressedPayload);
      return delegate.redact(format, charset, decompressedPayload, requestMetadata);
    }

    @Override
    public String version() {
      return delegate.version();
    }
  }
}
