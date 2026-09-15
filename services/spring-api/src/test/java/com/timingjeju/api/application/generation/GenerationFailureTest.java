package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class GenerationFailureTest {
  @Test
  void 비종료와_성공_상태는_과거_오류를_공개하지_않는다() {
    for (String status : List.of("queued", "running", "succeeded"))
      assertThat(GenerationFailure.from(status, "MCP_TIMEOUT")).isNull();
  }

  @Test
  void 알려진_실패만_고정_한국어_안내와_재시도_여부로_반환한다() {
    var failure = GenerationFailure.from("failed", "MCP_TIMEOUT");
    assertThat(failure.code()).isEqualTo("MCP_TIMEOUT");
    assertThat(failure.detail()).isEqualTo("일정 계산 시간이 초과되었습니다. 다시 시도해 주세요.");
    assertThat(failure.retryable()).isTrue();
    assertThat(GenerationFailure.from("failed", "MCP_CONTRACT_INVALID").retryable()).isFalse();
    assertThat(GenerationFailure.from("cancelled", "MCP_TIMEOUT").code())
        .isEqualTo("ASYNC_RUN_CANCELLED");
  }

  @Test
  void 알수없는_코드와_원문은_응답에_복사하지_않는다() {
    for (String code :
        new String[] {null, "", "provider body user@example.com", "UNKNOWN_PRIVATE_CODE"}) {
      var failure = GenerationFailure.from("failed", code);
      var json = JsonMapper.builder().build().valueToTree(failure);
      assertThat(json.properties())
          .extracting(java.util.Map.Entry::getKey)
          .containsExactlyInAnyOrder("code", "detail", "retryable");
      assertThat(failure.code()).isEqualTo("GENERATION_EXECUTION_FAILED");
      assertThat(json.toString())
          .doesNotContain("provider body", "user@example.com", "UNKNOWN_PRIVATE_CODE");
    }
  }
}
