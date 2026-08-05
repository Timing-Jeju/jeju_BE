package com.timingjeju.api.global.logging;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

public final class RequestTraceId {

  public static final String TRACE_ID_ATTRIBUTE = RequestTraceId.class.getName() + ".value";
  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";

  private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[0-9a-f]{32}");

  private final TraceIdGenerator generator;

  public RequestTraceId(TraceIdGenerator generator) {
    this.generator = generator;
  }

  public String getOrCreate(HttpServletRequest request) {
    Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
    if (existing instanceof String traceId && TRACE_ID_PATTERN.matcher(traceId).matches()) {
      return traceId;
    }
    String traceId = generator.generate();
    if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
      throw new IllegalStateException("TraceIdGenerator must return 32 lowercase hex characters");
    }
    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    return traceId;
  }
}
