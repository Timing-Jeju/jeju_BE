package com.timingjeju.api.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.global.snapshot.DeterministicSnapshotRedactor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SnapshotStoreServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
  private static final UUID SNAPSHOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000023");
  private final RecordingStore store = new RecordingStore();
  private SnapshotStoreService service;

  @BeforeEach
  void setUp() {
    service =
        new SnapshotStoreService(
            store,
            new DeterministicSnapshotRedactor(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> SNAPSHOT_ID);
  }

  @Test
  void JSON과_request_metadata의_중첩_sensitive_value를_결정적으로_제거한다() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("requestUrl", "https://provider.test/path?serviceKey=query-secret");
    metadata.put("Authorization", "Bearer metadata-secret");
    metadata.put("safePage", 3);
    metadata.put("callback", "https://provider.test/request/path?plain=value");
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("Cookie", "session=secret");
    nested.put("language", "ko");
    nested.put("nullable", null);
    metadata.put("nested", nested);
    byte[] payload =
        """
        {"safe":"kept","serviceKey":"payload-secret","profile":{"EMAIL":"user@example.test","name":"홍길동"},"location":{"lat":33.5001,"longitude":126.5002},"items":[{"Authorization":"Bearer nested-secret"}]}
        """
            .getBytes(StandardCharsets.UTF_8);

    SnapshotSaveResult result =
        service.save(command(SnapshotPayloadFormat.JSON, payload, metadata));

    StoredSnapshot saved = store.saved.getFirst();
    assertThat(result.replayed()).isFalse();
    assertThat(result.payloadHash()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(result.requestFingerprint()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(saved.redactionVersion()).isEqualTo("snapshot-redaction-v2");
    assertThat(saved.payloadSizeBytes()).isEqualTo(payload.length);
    assertThat(saved.rawPayloadJson())
        .contains("\"safe\":\"kept\"")
        .contains("\"[REDACTED]\"")
        .doesNotContain("payload-secret", "nested-secret", "user@example.test", "홍길동")
        .doesNotContain("33.5001", "126.5002");
    assertThat(saved.requestMetadataRedactedJson())
        .contains("\"safePage\":3", "\"language\":\"ko\"")
        .doesNotContain(
            "https://", "provider.test", "query-secret", "metadata-secret", "session=secret");
    assertThat(saved.purgeAfter()).isEqualTo(NOW.plus(SnapshotRetention.FAILED_OR_UNPARSED));

    service.save(command(SnapshotPayloadFormat.JSON, payload, metadata));
    assertThat(store.saved.get(1).requestHash()).isEqualTo(saved.requestHash());
    assertThat(store.saved.get(1).rawPayloadJson()).isEqualTo(saved.rawPayloadJson());
  }

  @Test
  void JSON_payload와_metadata는_PII_alias와_정밀좌표를_같은_registry로_제거하고_안전한_유사키는_보존한다() {
    Map<String, Object> metadata =
        Map.of(
            "first-name", "메타 이름",
            "USER_EMAIL", "meta@example.test",
            "account.id", "account-secret",
            "home_latitude", 33.5001,
            "placeName", "성산일출봉",
            "categoryName", "관광지",
            "contactless", true);
    byte[] payload =
        """
        {
          "profile": {
            "firstName": "길동",
            "last_name": "홍",
            "FULL.NAME": "홍길동",
            "userEmail": "user@example.test",
            "homePhone": "010-1234-5678",
            "postalAddress": "제주시 비밀 주소",
            "userId": "user-secret",
            "accountId": "account-secret",
            "deviceId": "device-secret"
          },
          "route": {"homeLatitude": 33.1, "pickupLongitude": 126.2},
          "placeName": "성산일출봉",
          "categoryName": "관광지",
          "contactless": true
        }
        """
            .getBytes(StandardCharsets.UTF_8);

    service.save(command(SnapshotPayloadFormat.JSON, payload, metadata));

    StoredSnapshot saved = store.saved.getFirst();
    assertThat(saved.rawPayloadJson())
        .contains("성산일출봉", "관광지", "contactless")
        .doesNotContain(
            "길동",
            "홍",
            "user@example.test",
            "010-1234-5678",
            "제주시 비밀 주소",
            "user-secret",
            "account-secret",
            "device-secret",
            "33.1",
            "126.2");
    assertThat(saved.requestMetadataRedactedJson())
        .contains("성산일출봉", "관광지", "contactless")
        .doesNotContain("메타 이름", "meta@example.test", "account-secret", "33.5001");
  }

  @Test
  void secret과_원문URL이_달라도_sanitized_request_fingerprint는_같다() {
    byte[] payload = "{\"safe\":1}".getBytes(StandardCharsets.UTF_8);
    SnapshotSaveResult first =
        service.save(
            command(
                SnapshotPayloadFormat.JSON,
                payload,
                Map.of(
                    "serviceKey", "first-secret",
                    "callback", "https://first-provider.test/path?key=first")));
    SnapshotSaveResult second =
        service.save(
            command(
                SnapshotPayloadFormat.JSON,
                payload,
                Map.of(
                    "serviceKey", "second-secret",
                    "callback", "https://second-provider.test/path?key=second")));

    assertThat(second.requestFingerprint()).isEqualTo(first.requestFingerprint());
    assertThat(store.saved)
        .allMatch(
            snapshot ->
                !snapshot.requestMetadataRedactedJson().contains("provider.test")
                    && !snapshot.requestMetadataRedactedJson().contains("secret"));
  }

  @Test
  void XML_attribute_element와_text_query의_secret_PII를_제거한다() {
    byte[] xml =
        """
        <response serviceKey="xml-secret"><item><name>사용자 이름</name><safe>유지</safe><nested Authorization="Bearer x"/></item></response>
        """
            .getBytes(StandardCharsets.UTF_8);
    service.save(command(SnapshotPayloadFormat.XML, xml, Map.of()));

    byte[] text =
        "serviceKey=text-secret&safe=kept\nAuthorization: Bearer text-token\nemail=user@example.test"
            .getBytes(StandardCharsets.UTF_8);
    service.save(command(SnapshotPayloadFormat.TEXT, text, Map.of("page", 1)));

    assertThat(store.saved.get(0).rawPayloadJson())
        .contains("유지", "[REDACTED]")
        .doesNotContain("xml-secret", "사용자 이름", "Bearer x");
    assertThat(store.saved.get(1).rawPayloadJson())
        .contains("safe=kept", "[REDACTED]")
        .doesNotContain("text-secret", "text-token", "user@example.test");
  }

  @Test
  void XML_namespace의_localName으로_element와_attribute를_제거하고_안전한_namespace는_보존한다() {
    byte[] xml =
        """
        <response xmlns="urn:safe" xmlns:sec="urn:secret" xmlns:user="urn:user" xmlns:catalog="https://schemas.example.test/safe">
          <sec:serviceKey>namespace-service-key</sec:serviceKey>
          <token>namespace-token</token>
          <item user:userEmail="namespace@example.test" user:deviceId="device-secret">
            <user:fullName>홍길동</user:fullName>
            <homeLatitude>33.5001</homeLatitude>
            <sec:pickupLongitude>126.5002</sec:pickupLongitude>
            <placeName>성산일출봉</placeName>
            <catalog:categoryName>관광지</catalog:categoryName>
          </item>
        </response>
        """
            .getBytes(StandardCharsets.UTF_8);

    service.save(command(SnapshotPayloadFormat.XML, xml, Map.of()));

    assertThat(store.saved.getFirst().rawPayloadJson())
        .contains(
            "urn:safe",
            "urn:secret",
            "urn:user",
            "https://schemas.example.test/safe",
            "성산일출봉",
            "관광지")
        .doesNotContain(
            "namespace-service-key",
            "namespace-token",
            "namespace@example.test",
            "device-secret",
            "홍길동",
            "33.5001",
            "126.5002");
  }

  @Test
  void malformed_구조와_binary_및_잘못된_charset은_raw를_남기지_않고_안전한_terminal로_저장한다() {
    service.save(
        command(
            SnapshotPayloadFormat.JSON,
            "{\"serviceKey\":\"must-not-survive\"".getBytes(StandardCharsets.UTF_8),
            Map.of()));
    service.save(
        command(SnapshotPayloadFormat.BINARY, new byte[] {0, 1, 2, 3}, Map.of("token", "x")));
    SnapshotSaveCommand invalidUtf8 =
        command(SnapshotPayloadFormat.TEXT, new byte[] {(byte) 0xC3, (byte) 0x28}, Map.of());
    service.save(invalidUtf8);

    assertThat(store.saved).hasSize(3);
    assertThat(store.saved.get(0).status()).isEqualTo(SnapshotStatus.REJECTED);
    assertThat(store.saved.get(0).errorCode()).isEqualTo("SNAPSHOT_MALFORMED_PAYLOAD");
    assertThat(store.saved.get(1).status()).isEqualTo(SnapshotStatus.IGNORED);
    assertThat(store.saved.get(1).errorCode()).isEqualTo("SNAPSHOT_BINARY_PAYLOAD");
    assertThat(store.saved.get(2).status()).isEqualTo(SnapshotStatus.REJECTED);
    assertThat(store.saved).allMatch(snapshot -> snapshot.rawPayloadJson() == null);
    assertThat(store.saved)
        .allMatch(
            snapshot ->
                snapshot.purgeAfter().equals(NOW.plus(SnapshotRetention.FAILED_OR_UNPARSED)));
  }

  @Test
  void decompressed_2MiB를_초과하면_hash_redaction_DB호출_전에_거부한다() {
    byte[] oversized = new byte[SnapshotStoreService.MAX_DECOMPRESSED_PAYLOAD_BYTES + 1];

    assertThatThrownBy(
            () -> service.save(command(SnapshotPayloadFormat.BINARY, oversized, Map.of())))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.PAYLOAD_TOO_LARGE);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void decompressed_2MiB_정확한_경계는_저장하고_command와_stored_toString에_원문을_노출하지_않는다() {
    byte[] boundary = new byte[SnapshotStoreService.MAX_DECOMPRESSED_PAYLOAD_BYTES];
    SnapshotSaveCommand command =
        command(SnapshotPayloadFormat.BINARY, boundary, Map.of("token", "metadata-secret"));

    service.save(command);

    assertThat(store.saved.getFirst().payloadSizeBytes())
        .isEqualTo(SnapshotStoreService.MAX_DECOMPRESSED_PAYLOAD_BYTES);
    assertThat(command.toString()).doesNotContain("metadata-secret");
    assertThat(store.saved.getFirst().toString()).doesNotContain("metadata-secret", "rawPayload");
  }

  @Test
  void payload_hash는_decompressed_원문_byte_exact이고_charset은_UTF8만_허용한다() {
    SnapshotSaveResult compact =
        service.save(
            command(
                SnapshotPayloadFormat.JSON,
                "{\"safe\":1}".getBytes(StandardCharsets.UTF_8),
                Map.of()));
    SnapshotSaveResult spaced =
        service.save(
            command(
                SnapshotPayloadFormat.JSON,
                "{ \"safe\" : 1 }".getBytes(StandardCharsets.UTF_8),
                Map.of()));

    assertThat(compact.payloadHash()).isNotEqualTo(spaced.payloadHash());
    assertThat(store.saved.get(0).rawPayloadJson()).isEqualTo(store.saved.get(1).rawPayloadJson());
    assertThatThrownBy(
            () ->
                service.save(
                    new SnapshotSaveCommand(
                        UUID.randomUUID(),
                        scope(),
                        null,
                        "1",
                        200,
                        null,
                        NOW,
                        null,
                        null,
                        "parser-v1",
                        SnapshotPayloadFormat.TEXT,
                        "EUC-KR",
                        "safe".getBytes(StandardCharsets.UTF_8),
                        Map.of())))
        .isInstanceOf(SnapshotStoreException.class)
        .extracting("code")
        .isEqualTo(SnapshotStoreError.UNSUPPORTED_CHARSET);
  }

  @Test
  void received에서만_terminal로_전환하고_보존기간과_오류문구를_고정한다() {
    service.transition(new SnapshotTransitionCommand(SNAPSHOT_ID, SnapshotStatus.PARSED, null));
    assertThat(store.transitions.getFirst().retention()).isEqualTo(SnapshotRetention.SUCCESSFUL);
    assertThat(store.transitions.getFirst().errorCode()).isNull();

    service.transition(
        new SnapshotTransitionCommand(
            SNAPSHOT_ID, SnapshotStatus.REJECTED, SnapshotFailure.PARSE_REJECTED));
    assertThat(store.transitions.get(1).retention())
        .isEqualTo(SnapshotRetention.FAILED_OR_UNPARSED);
    assertThat(store.transitions.get(1).errorCode()).isEqualTo("SNAPSHOT_PARSE_REJECTED");
    assertThat(store.transitions.get(1).errorMessage()).isEqualTo("원천 응답을 안전하게 해석하지 못했습니다.");

    for (SnapshotStatus invalid : List.of(SnapshotStatus.RECEIVED)) {
      assertThatThrownBy(
              () -> service.transition(new SnapshotTransitionCommand(SNAPSHOT_ID, invalid, null)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private SnapshotSaveCommand command(
      SnapshotPayloadFormat format, byte[] payload, Map<String, Object> metadata) {
    return new SnapshotSaveCommand(
        UUID.fromString("20000000-0000-0000-0000-000000000023"),
        scope(),
        "record-1",
        "page-1",
        200,
        "00",
        NOW,
        null,
        null,
        "parser-v1",
        format,
        "UTF-8",
        payload,
        metadata);
  }

  private static SnapshotScope scope() {
    return new SnapshotScope("tour-api", "KorService2", "areaBasedList2", "jeju");
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
}
