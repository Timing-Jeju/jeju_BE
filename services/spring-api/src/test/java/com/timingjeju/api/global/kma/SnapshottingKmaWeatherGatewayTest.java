package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.kma.KmaWeatherImportCommand;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.application.kma.KmaWeatherResponsePart;
import com.timingjeju.api.application.kma.KmaWeatherSourceResponse;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotSaveCommand;
import com.timingjeju.api.application.snapshot.SnapshotSaveResult;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.snapshot.SnapshotStoreService;
import com.timingjeju.api.application.snapshot.SnapshotTransitionCommand;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class SnapshottingKmaWeatherGatewayTest {
  private static final UUID RUN = UUID.randomUUID();
  private static final UUID SNAPSHOT = UUID.randomUUID();
  private static final Instant FETCHED = Instant.parse("2026-08-15T15:45:01Z");

  @Test
  void capturesRawPayloadAndOnlyRedactionSafeRequestMetadata() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any())).thenReturn(saved(false, SnapshotStatus.RECEIVED));
    SnapshottingKmaWeatherGateway gateway =
        new SnapshottingKmaWeatherGateway(store, Clock.fixed(FETCHED, ZoneOffset.UTC));
    byte[] raw = "{\"response\":{}}".getBytes(StandardCharsets.UTF_8);

    gateway.capture(
        RUN,
        KmaWeatherOperation.ULTRA_FORECAST,
        new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(0, 30)),
        new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "secret-looking-idempotency"),
        new KmaWeatherSourceResponse(raw, SnapshotPayloadFormat.JSON));

    ArgumentCaptor<SnapshotSaveCommand> captured =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(store).save(captured.capture());
    SnapshotSaveCommand command = captured.getValue();
    assertThat(command.scope().provider()).isEqualTo("kma");
    assertThat(command.scope().operation()).isEqualTo("getUltraSrtFcst");
    assertThat(command.scope().scopeKey()).isEqualTo("nx=52;ny=38");
    assertThat(command.decompressedPayload()).isEqualTo(raw);
    assertThat(command.requestMetadata())
        .containsEntry("endpoint", "/getUltraSrtFcst")
        .containsEntry("base_date", "20260816")
        .containsEntry("base_time", "0030")
        .containsEntry("nx", "52")
        .containsEntry("ny", "38")
        .doesNotContainKeys(
            "serviceKey",
            "ServiceKey",
            "apiKey",
            "Authorization",
            "query",
            "url",
            "idempotencyKey");
    assertThat(command.toString()).doesNotContain("secret-looking-idempotency");
  }

  @Test
  void capturesEveryExactVillageResponseThenAnOrderedMetadataOnlyManifest() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    UUID pageOneId = UUID.randomUUID();
    UUID pageTwoId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID manifestId = UUID.randomUUID();
    when(store.save(any()))
        .thenReturn(
            saved(pageOneId, "1".repeat(64)),
            saved(pageTwoId, "2".repeat(64)),
            saved(versionId, "3".repeat(64)),
            saved(manifestId, "4".repeat(64)));
    SnapshottingKmaWeatherGateway gateway =
        new SnapshottingKmaWeatherGateway(store, Clock.fixed(FETCHED, ZoneOffset.UTC));
    byte[] pageOne = " {\"response\":{},\"amount\":1.00}\n".getBytes(StandardCharsets.UTF_8);
    byte[] pageTwo = "{\"amount\":1e+0,\"response\":{}}".getBytes(StandardCharsets.UTF_8);
    byte[] version = "\n{\"response\":{}}".getBytes(StandardCharsets.UTF_8);

    gateway.capture(
        RUN,
        KmaWeatherOperation.VILLAGE_FORECAST,
        new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
        new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "village-run"),
        new KmaWeatherSourceResponse(
            List.of(
                new KmaWeatherResponsePart("getVilageFcst", 1, pageOne, SnapshotPayloadFormat.JSON),
                new KmaWeatherResponsePart("getVilageFcst", 2, pageTwo, SnapshotPayloadFormat.JSON),
                new KmaWeatherResponsePart(
                    "getFcstVersion", 1, version, SnapshotPayloadFormat.JSON))));

    ArgumentCaptor<SnapshotSaveCommand> captured =
        ArgumentCaptor.forClass(SnapshotSaveCommand.class);
    verify(store, org.mockito.Mockito.times(4)).save(captured.capture());
    List<SnapshotSaveCommand> commands = captured.getAllValues();
    assertThat(commands.subList(0, 3))
        .extracting(SnapshotSaveCommand::decompressedPayload)
        .containsExactly(pageOne, pageTwo, version);
    assertThat(commands.subList(0, 3))
        .extracting(command -> command.requestMetadata().get("responseOperation"))
        .containsExactly("getVilageFcst", "getVilageFcst", "getFcstVersion");
    assertThat(commands.subList(0, 3))
        .extracting(SnapshotSaveCommand::pageKey)
        .containsExactly("getVilageFcst:1", "getVilageFcst:2", "getFcstVersion:1");
    assertThat(commands.getLast().parserVersion())
        .isEqualTo(
            com.timingjeju.api.application.kma.KmaWeatherImportService.VILLAGE_PARSER_VERSION);
    String manifest = new String(commands.getLast().decompressedPayload(), StandardCharsets.UTF_8);
    assertThat(manifest)
        .contains(pageOneId.toString(), pageTwoId.toString(), versionId.toString())
        .contains("1".repeat(64), "2".repeat(64), "3".repeat(64))
        .doesNotContain("\"response\":{}", "1.00", "1e+0");
  }

  @Test
  void parsedReplayDoesNotRepeatTerminalTransition() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any())).thenReturn(saved(true, SnapshotStatus.PARSED));
    SnapshottingKmaWeatherGateway gateway =
        new SnapshottingKmaWeatherGateway(store, Clock.fixed(FETCHED, ZoneOffset.UTC));
    var snapshot =
        gateway.capture(
            RUN,
            KmaWeatherOperation.ULTRA_CURRENT,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.MIDNIGHT),
            new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "run-key"),
            new KmaWeatherSourceResponse(new byte[0], SnapshotPayloadFormat.JSON));

    gateway.markParsed(snapshot);

    verify(store, never()).transition(any());
  }

  @Test
  void receivedSnapshotTransitionsToParsed() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    when(store.save(any())).thenReturn(saved(false, SnapshotStatus.RECEIVED));
    SnapshottingKmaWeatherGateway gateway =
        new SnapshottingKmaWeatherGateway(store, Clock.fixed(FETCHED, ZoneOffset.UTC));
    var snapshot =
        gateway.capture(
            RUN,
            KmaWeatherOperation.ULTRA_CURRENT,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.MIDNIGHT),
            new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "run-key"),
            new KmaWeatherSourceResponse(new byte[0], SnapshotPayloadFormat.JSON));

    gateway.markParsed(snapshot);

    verify(store).transition(new SnapshotTransitionCommand(SNAPSHOT, SnapshotStatus.PARSED, null));
  }

  @Test
  void rejectedVillageAttemptTransitionsEveryExactResponseAndManifestForAudit() {
    SnapshotStoreService store = mock(SnapshotStoreService.class);
    UUID pageOne = UUID.randomUUID();
    UUID pageTwoError = UUID.randomUUID();
    UUID manifest = UUID.randomUUID();
    when(store.save(any()))
        .thenReturn(
            saved(pageOne, "1".repeat(64)),
            saved(pageTwoError, "2".repeat(64)),
            saved(manifest, "3".repeat(64)));
    SnapshottingKmaWeatherGateway gateway =
        new SnapshottingKmaWeatherGateway(store, Clock.fixed(FETCHED, ZoneOffset.UTC));
    var snapshot =
        gateway.capture(
            RUN,
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            new KmaWeatherImportCommand(UUID.randomUUID(), 52, 38, "page2-error"),
            new KmaWeatherSourceResponse(
                List.of(
                    new KmaWeatherResponsePart(
                        "getVilageFcst",
                        1,
                        " first ".getBytes(StandardCharsets.UTF_8),
                        SnapshotPayloadFormat.JSON),
                    new KmaWeatherResponsePart(
                        "getVilageFcst",
                        2,
                        " error ".getBytes(StandardCharsets.UTF_8),
                        SnapshotPayloadFormat.JSON))));

    gateway.markRejected(snapshot);

    verify(store)
        .transition(
            new SnapshotTransitionCommand(
                pageOne,
                SnapshotStatus.REJECTED,
                com.timingjeju.api.application.snapshot.SnapshotFailure.PARSE_REJECTED));
    verify(store)
        .transition(
            new SnapshotTransitionCommand(
                pageTwoError,
                SnapshotStatus.REJECTED,
                com.timingjeju.api.application.snapshot.SnapshotFailure.PARSE_REJECTED));
    verify(store)
        .transition(
            new SnapshotTransitionCommand(
                manifest,
                SnapshotStatus.REJECTED,
                com.timingjeju.api.application.snapshot.SnapshotFailure.PARSE_REJECTED));
  }

  private static SnapshotSaveResult saved(boolean replayed, SnapshotStatus status) {
    return new SnapshotSaveResult(
        SNAPSHOT, "a".repeat(64), "b".repeat(64), replayed, FETCHED, status);
  }

  private static SnapshotSaveResult saved(UUID id, String hash) {
    return new SnapshotSaveResult(
        id, "a".repeat(64), hash, false, FETCHED, SnapshotStatus.RECEIVED);
  }
}
