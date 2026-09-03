package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class McpEndpointPolicyTest {

  @Test
  void MCP_endpoint는_HTTPS와_명시한_private_host만_허용한다() {
    assertThatCode(
            () ->
                McpEndpointPolicy.requirePrivateHttps(
                    URI.create("https://timing-jeju-ai:8000"), "timing-jeju-ai"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                McpEndpointPolicy.requirePrivateHttps(
                    URI.create("https://127.0.0.1:8000"), "127.0.0.1"))
        .doesNotThrowAnyException();

    for (URI invalid :
        new URI[] {
          URI.create("http://timing-jeju-ai:8000"),
          URI.create("https://example.com"),
          URI.create("https://user:secret@timing-jeju-ai:8000"),
          URI.create("https://timing-jeju-ai:8000?token=secret")
        }) {
      assertThatThrownBy(() -> McpEndpointPolicy.requirePrivateHttps(invalid, "timing-jeju-ai"))
          .as(invalid.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
