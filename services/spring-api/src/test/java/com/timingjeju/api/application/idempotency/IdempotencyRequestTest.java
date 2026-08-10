package com.timingjeju.api.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IdempotencyRequestTest {

  private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000017");
  private static final String KEY = "018f6f2a-60a0-7f5b-8c61-8f548f34bc31";

  @Test
  void 누락된_key는_required_code로_거부한다() {
    assertThatThrownBy(
            () -> IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", null, bytes("{}")))
        .isInstanceOf(IdempotencyException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
  }

  @Test
  void UUID가_아닌_key는_invalid_code로_거부한다() {
    assertThatThrownBy(
            () ->
                IdempotencyRequest.create(
                    OWNER, "POST", "/api/v1/trips", "not-a-uuid", bytes("{}")))
        .isInstanceOf(IdempotencyException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_KEY_INVALID");
  }

  @Test
  void request_body가_정확히_1MiB이면_허용한다() {
    IdempotencyRequest request =
        IdempotencyRequest.create(OWNER, "post", "/api//v1/trips/", KEY, new byte[1_048_576]);

    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.normalizedPath()).isEqualTo("/api/v1/trips");
    assertThat(request.body()).hasSize(1_048_576);
  }

  @Test
  void request_body가_1MiB를_넘으면_거부한다() {
    assertThatThrownBy(
            () ->
                IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, new byte[1_048_577]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 MiB");
  }

  @Test
  void canonical_hash는_method와_normalized_path와_body가_같으면_동일하다() {
    IdempotencyRequest first =
        IdempotencyRequest.create(OWNER, "post", "/api//v1/trips/", KEY, bytes("{\"a\":1}"));
    IdempotencyRequest second =
        IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, bytes("{\"a\":1}"));

    assertThat(first.requestHash()).isEqualTo(second.requestHash()).hasSize(64);
  }

  @Test
  void body가_다르면_canonical_hash도_다르다() {
    IdempotencyRequest first =
        IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, bytes("{\"a\":1}"));
    IdempotencyRequest second =
        IdempotencyRequest.create(OWNER, "POST", "/api/v1/trips", KEY, bytes("{\"a\":2}"));

    assertThat(first.requestHash()).isNotEqualTo(second.requestHash());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
