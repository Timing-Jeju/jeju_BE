package com.timingjeju.api.domain.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleIdempotencyKeyTest {
  private static final String UUID_KEY = "018f6f2a-60a0-7f5b-8c61-8f548f34bc31";
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000001");
  private static final String PATH =
      "/api/v1/trips/49000000-0000-0000-0000-000000000002/schedule-items";

  @Test
  void 한글자와_128자_printable_ASCII를_결정적인_registry_UUID로_변환한다() {
    String minimumInput = " ";
    String maximumInput = "~".repeat(128);
    String minimumRegistryKey = ScheduleIdempotencyKey.toRegistryKey(minimumInput);
    String maximumRegistryKey = ScheduleIdempotencyKey.toRegistryKey(maximumInput);

    assertThat(UUID.fromString(minimumRegistryKey).toString()).isEqualTo(minimumRegistryKey);
    assertThat(UUID.fromString(maximumRegistryKey).toString()).isEqualTo(maximumRegistryKey);
    assertThat(ScheduleIdempotencyKey.toRegistryKey("printable-key"))
        .isEqualTo(ScheduleIdempotencyKey.toRegistryKey("printable-key"))
        .isEqualTo("ba8cb334-92ef-8999-8eb6-8b95ecd8bb71");
    assertThat(minimumRegistryKey).isNotEqualTo(maximumRegistryKey);
  }

  @Test
  void printable_key의_space와_case는_서로_다른_registry_scope를_유지한다() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    IdempotencyRequest lower =
        ScheduleIdempotencyKey.createRequest(OWNER, "POST", PATH, "printable-key", body);
    IdempotencyRequest upper =
        ScheduleIdempotencyKey.createRequest(OWNER, "POST", PATH, "Printable-Key", body);
    IdempotencyRequest spaced =
        ScheduleIdempotencyKey.createRequest(OWNER, "POST", PATH, "printable key", body);

    assertThat(lower.scope()).isNotEqualTo(upper.scope()).isNotEqualTo(spaced.scope());
    assertThat(upper.scope()).isNotEqualTo(spaced.scope());
  }

  @Test
  void 기존_lowercase_canonical_UUID는_registry_scope를_바꾸지_않는다() {
    assertThat(ScheduleIdempotencyKey.toRegistryKey(UUID_KEY)).isEqualTo(UUID_KEY);
  }

  @Test
  void 해시_UUID와_동일한_UUID_원문은_durable_scope가_분리되고_canonical_scope는_보존된다() {
    String collidingCanonicalUuid = "ba8cb334-92ef-8999-8eb6-8b95ecd8bb71";
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

    IdempotencyRequest printable =
        ScheduleIdempotencyKey.createRequest(OWNER, "POST", PATH, "printable-key", body);
    IdempotencyRequest canonical =
        ScheduleIdempotencyKey.createRequest(OWNER, "POST", PATH, collidingCanonicalUuid, body);

    assertThat(printable.idempotencyKey()).isEqualTo(canonical.idempotencyKey());
    assertThat(printable.scope()).isNotEqualTo(canonical.scope());
    assertThat(printable.normalizedPath())
        .startsWith(PATH + "/.idempotency/schedule-printable-ascii-v1-")
        .doesNotContain("printable-key")
        .isNotEqualTo(PATH);
    assertThat(
            printable.normalizedPath().substring(printable.normalizedPath().lastIndexOf('-') + 1))
        .matches("[0-9a-f]{64}");
    assertThat(canonical.normalizedPath()).isEqualTo(PATH);
  }

  @Test
  void null과_빈값은_required이고_129자_control_non_ASCII는_invalid다() {
    for (String missing : new String[] {null, ""}) {
      assertThatThrownBy(() -> ScheduleIdempotencyKey.toRegistryKey(missing))
          .isInstanceOf(IdempotencyException.class)
          .extracting("code")
          .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
    for (String invalid : new String[] {"x".repeat(129), "line\nbreak", "제주"}) {
      assertThatThrownBy(() -> ScheduleIdempotencyKey.toRegistryKey(invalid))
          .isInstanceOf(IdempotencyException.class)
          .extracting("code")
          .isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }
  }
}
