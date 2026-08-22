package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.importing.ImportRunLease;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tago.arrival.SavedTagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitResult;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitter;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalImportSession;
import com.timingjeju.api.application.tago.arrival.TagoArrivalLoadService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshotGateway;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class TagoArrivalParserTest {
  private final TagoArrivalParser parser = new TagoArrivalParser(new ObjectMapper());

  @Test
  void 공식_envelope의_숫자형_노선과_도착정보를_손실없이_정규화한다() throws IOException {
    byte[] payload = recordedNumericFixture();

    assertThat(new String(payload, StandardCharsets.UTF_8)).doesNotContain("serviceKey");
    assertThat(parser.parse(SnapshotPayloadFormat.JSON, payload))
        .containsExactly(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4));
  }

  @Test
  void 공식_숫자형_fixture의_동일_bytes를_snapshot한뒤_parse하고_commit한다() throws IOException {
    byte[] payload = recordedNumericFixture();
    Instant now = Instant.parse("2026-08-22T00:00:00Z");
    UUID runId = UUID.fromString("39000000-0000-0000-0000-000000000010");
    UUID snapshotId = UUID.fromString("39000000-0000-0000-0000-000000000012");
    ImportRunLease lease =
        new ImportRunLease(runId, UUID.fromString("39000000-0000-0000-0000-000000000011"), 1);
    TagoArrivalImportSession session = mock(TagoArrivalImportSession.class);
    TagoArrivalSnapshotGateway snapshots = mock(TagoArrivalSnapshotGateway.class);
    TagoArrivalCommitter committer = mock(TagoArrivalCommitter.class);
    when(session.start(any(), any())).thenReturn(lease);
    when(snapshots.capture(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new SavedTagoArrivalSnapshot(
                    invocation.getArgument(2),
                    snapshotId,
                    "a".repeat(64),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    false,
                    SnapshotStatus.RECEIVED));
    when(committer.commit(any())).thenReturn(new TagoArrivalCommitResult(1));
    TagoArrivalLoadService service =
        new TagoArrivalLoadService(
            (cityCode, nodeId) ->
                new TagoArrivalSourceResponse(payload, SnapshotPayloadFormat.JSON),
            parser,
            session,
            snapshots,
            committer,
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofSeconds(25));
    TagoArrivalCacheKey key =
        TagoArrivalCacheKey.tago(
            UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");

    assertThat(service.load(key).arrivals())
        .containsExactly(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4));
    ArgumentCaptor<TagoArrivalSourceResponse> captured =
        ArgumentCaptor.forClass(TagoArrivalSourceResponse.class);
    verify(snapshots).capture(any(), any(), captured.capture(), any(), any());
    assertThat(captured.getValue().payload()).isSameAs(payload);
    ArgumentCaptor<TagoArrivalCommitCommand> committed =
        ArgumentCaptor.forClass(TagoArrivalCommitCommand.class);
    verify(committer).commit(committed.capture());
    assertThat(committed.getValue().snapshot().storedResponse().payload()).isSameAs(payload);
    assertThat(committed.getValue().arrivals())
        .extracting(TagoArrival::routeNo)
        .containsExactly("201");
  }

  @Test
  void 기존_문자열형과_영숫자_노선번호를_호환한다() {
    byte[] payload = success("321", "4");

    assertThat(parser.parse(SnapshotPayloadFormat.JSON, payload))
        .containsExactly(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4));
    assertThat(parser.parse(SnapshotPayloadFormat.JSON, successRaw("\"201A\"", "\"321\"", "\"4\"")))
        .extracting(TagoArrival::routeNo)
        .containsExactly("201A");
  }

  @Test
  void provider_97과_성공_empty를_구분한다() {
    byte[] quota =
        "{\"response\":{\"header\":{\"resultCode\":\"97\",\"resultMsg\":\"LIMITED NUMBER OF SERVICE REQUESTS EXCEEDS ERROR.\"},\"body\":{}}}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] empty =
        "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},\"body\":{\"items\":{},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":0}}}"
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, quota))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.RATE_LIMITED));
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, empty))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.EMPTY_RESULT));
  }

  @Test
  void 숫자형_도착초와_남은정류장의_양끝_경계를_허용한다() {
    assertThat(parser.parse(SnapshotPayloadFormat.JSON, successRaw("201", "0", "0")))
        .extracting(TagoArrival::estimatedArrivalSeconds, TagoArrival::remainingStops)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 0));
    assertThat(parser.parse(SnapshotPayloadFormat.JSON, successRaw("201", "86400", "10000")))
        .extracting(TagoArrival::estimatedArrivalSeconds, TagoArrival::remainingStops)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(86_400, 10_000));
  }

  @Test
  void 정수_범위를_벗어난_도착초와_남은정류장을_거부한다() {
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("-1", "4")))
        .isInstanceOf(TagoArrivalException.class);
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("86401", "4")))
        .isInstanceOf(TagoArrivalException.class);
    assertThatThrownBy(() -> parser.parse(SnapshotPayloadFormat.JSON, success("321", "-1")))
        .isInstanceOf(TagoArrivalException.class);
    assertThatThrownBy(
            () -> parser.parse(SnapshotPayloadFormat.JSON, successRaw("201", "321", "10001")))
        .isInstanceOf(TagoArrivalException.class);
  }

  @Test
  void 숫자형_도착초와_남은정류장의_fraction_boolean_object_null_overflow를_거부한다() {
    for (String invalid :
        new String[] {"321.0", "321.5", "true", "{}", "null", "9223372036854775808"}) {
      assertThatThrownBy(
              () -> parser.parse(SnapshotPayloadFormat.JSON, successRaw("201", invalid, "4")))
          .as("arrtime=%s", invalid)
          .isInstanceOf(TagoArrivalException.class);
      assertThatThrownBy(
              () -> parser.parse(SnapshotPayloadFormat.JSON, successRaw("201", "321", invalid)))
          .as("arrprevstationcnt=%s", invalid)
          .isInstanceOf(TagoArrivalException.class);
    }
  }

  @Test
  void 숫자형_노선번호의_non_integral과_비scalar와_길이초과를_거부한다() {
    for (String invalid : new String[] {"201.0", "true", "{}", "null", "1".repeat(65)}) {
      assertThatThrownBy(
              () -> parser.parse(SnapshotPayloadFormat.JSON, successRaw(invalid, "321", "4")))
          .as("routeno=%s", invalid)
          .isInstanceOf(TagoArrivalException.class);
    }
  }

  private static byte[] success(String arrivalSeconds, String remainingStops) {
    return successRaw("\"201\"", "\"" + arrivalSeconds + "\"", "\"" + remainingStops + "\"");
  }

  private static byte[] successRaw(String routeNo, String arrivalSeconds, String remainingStops) {
    return ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},\"body\":{\"items\":{\"item\":[{\"routeid\":\"JER001\",\"routeno\":"
            + routeNo
            + ",\"routetp\":\"간선버스\",\"vehicletp\":\"일반차량\",\"arrtime\":"
            + arrivalSeconds
            + ",\"arrprevstationcnt\":"
            + remainingStops
            + "}]},\"numOfRows\":100,\"pageNo\":1,\"totalCount\":1}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] recordedNumericFixture() throws IOException {
    try (var input =
        TagoArrivalParserTest.class.getResourceAsStream(
            "/fixtures/tago/get-station-arrivals-numeric.json")) {
      if (input == null) throw new IOException("recorded TAGO arrival fixture가 없습니다.");
      return input.readAllBytes();
    }
  }
}
