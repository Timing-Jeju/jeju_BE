package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.kma.KmaWeatherImportCommand;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
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

  private static SnapshotSaveResult saved(boolean replayed, SnapshotStatus status) {
    return new SnapshotSaveResult(
        SNAPSHOT, "a".repeat(64), "b".repeat(64), replayed, FETCHED, status);
  }
}
