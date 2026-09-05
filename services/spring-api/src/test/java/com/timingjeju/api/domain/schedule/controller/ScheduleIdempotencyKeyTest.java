package com.timingjeju.api.domain.schedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.idempotency.IdempotencyException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleIdempotencyKeyTest {
  private static final String UUID_KEY = "018f6f2a-60a0-7f5b-8c61-8f548f34bc31";

  @Test
  void 한글자와_128자_printable_ASCII를_결정적인_registry_UUID로_변환한다() {
    String minimumInput = "!";
    String maximumInput = "~".repeat(128);
    String minimumRegistryKey = ScheduleIdempotencyKey.toRegistryKey(minimumInput);
    String maximumRegistryKey = ScheduleIdempotencyKey.toRegistryKey(maximumInput);

    assertThat(UUID.fromString(minimumRegistryKey).toString()).isEqualTo(minimumRegistryKey);
    assertThat(UUID.fromString(maximumRegistryKey).toString()).isEqualTo(maximumRegistryKey);
    assertThat(ScheduleIdempotencyKey.toRegistryKey("printable-key"))
        .isEqualTo(ScheduleIdempotencyKey.toRegistryKey("printable-key"));
    assertThat(minimumRegistryKey).isNotEqualTo(maximumRegistryKey);
  }

  @Test
  void 기존_lowercase_canonical_UUID는_registry_scope를_바꾸지_않는다() {
    assertThat(ScheduleIdempotencyKey.toRegistryKey(UUID_KEY)).isEqualTo(UUID_KEY);
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
