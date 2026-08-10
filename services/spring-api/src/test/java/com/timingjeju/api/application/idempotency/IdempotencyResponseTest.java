package com.timingjeju.api.application.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IdempotencyResponseTest {

  @Test
  void Authorization과_Set_Cookie_header는_저장을_거부한다() {
    assertThatThrownBy(() -> new IdempotencyHeader("Authorization", "Bearer secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyHeader("Set-Cookie", "session=secret"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void response_body는_정확히_1MiB까지_허용한다() {
    new IdempotencyResponse(201, List.of(), new byte[1_048_576]);
  }
}
