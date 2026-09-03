package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class McpFailureClassifierTest {

  @Test
  void 인증_timeout_malformed_JSON_RPC를_raw_cause없이_stable_code로_분류한다() {
    WebClientResponseException unauthorized =
        WebClientResponseException.create(
            401,
            "provider raw unauthorized",
            HttpHeaders.EMPTY,
            "provider raw body".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    McpRemoteCallException auth =
        McpFailureClassifier.classify(new McpTransportException("raw URL", unauthorized));
    McpRemoteCallException timeout =
        McpFailureClassifier.classify(
            new McpTransportException("raw timeout", new TimeoutException("raw host")));
    McpRemoteCallException protocol =
        McpFailureClassifier.classify(
            new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-32700, "raw malformed")));

    assertThat(auth.stableCode()).isEqualTo("MCP_AUTHENTICATION_FAILED");
    assertThat(auth.retryable()).isFalse();
    assertThat(timeout.stableCode()).isEqualTo("MCP_TIMEOUT");
    assertThat(timeout.retryable()).isTrue();
    assertThat(protocol.stableCode()).isEqualTo("MCP_PROTOCOL_INVALID");
    assertThat(protocol.retryable()).isFalse();
    assertThat(auth).hasNoCause();
    assertThat(timeout).hasNoCause();
    assertThat(protocol).hasNoCause();
  }
}
