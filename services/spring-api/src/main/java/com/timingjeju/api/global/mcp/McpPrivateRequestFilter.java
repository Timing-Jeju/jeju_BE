package com.timingjeju.api.global.mcp;

import com.timingjeju.api.global.logging.RequestTraceId;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

final class McpPrivateRequestFilter {
  private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[0-9a-f]{32}");

  private McpPrivateRequestFilter() {}

  static ExchangeFilterFunction create(McpServiceJwtIssuer jwtIssuer) {
    Objects.requireNonNull(jwtIssuer, "jwtIssuer는 필수입니다.");
    return (request, next) -> {
      ClientRequest.Builder authenticated =
          ClientRequest.from(request).headers(headers -> headers.setBearerAuth(jwtIssuer.issue()));
      String traceId = MDC.get(RequestTraceId.MDC_KEY);
      if (traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches()) {
        authenticated.header(RequestTraceId.TRACE_ID_HEADER, traceId);
      } else {
        authenticated.headers(headers -> headers.remove(RequestTraceId.TRACE_ID_HEADER));
      }
      return next.exchange(authenticated.build());
    };
  }
}
