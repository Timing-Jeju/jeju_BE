package com.timingjeju.api.global.mcp;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

final class McpFailureClassifier {
  private McpFailureClassifier() {}

  static McpRemoteCallException classify(RuntimeException exception) {
    if (exception instanceof McpRemoteCallException known) return known;
    if (hasCause(exception, WebClientResponseException.Unauthorized.class)
        || hasCause(exception, WebClientResponseException.Forbidden.class)) {
      return new McpRemoteCallException("MCP_AUTHENTICATION_FAILED", false);
    }
    if (hasCause(exception, McpError.class)) {
      return new McpRemoteCallException("MCP_PROTOCOL_INVALID", false);
    }
    if (hasCause(exception, TimeoutException.class)
        || hasCause(exception, HttpTimeoutException.class)) {
      return new McpRemoteCallException("MCP_TIMEOUT", true);
    }
    if (hasCause(exception, McpTransportException.class)) {
      return new McpRemoteCallException("MCP_TRANSPORT_UNAVAILABLE", true);
    }
    return new McpRemoteCallException("MCP_INTERNAL_ERROR", false);
  }

  private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
    Throwable current = exception;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (type.isInstance(current)) return true;
      current = current.getCause();
    }
    return false;
  }
}
