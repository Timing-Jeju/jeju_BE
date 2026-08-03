package com.timingjeju.api.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Callable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RequestTraceIdFilter extends OncePerRequestFilter implements Ordered {

  private static final String CALLABLE_INTERCEPTOR_KEY =
      RequestTraceIdFilter.class.getName() + ".callable";

  private final RequestTraceId requestTraceId;

  public RequestTraceIdFilter(RequestTraceId requestTraceId) {
    this.requestTraceId = requestTraceId;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = requestTraceId.getOrCreate(request);
    response.setHeader(RequestTraceId.TRACE_ID_HEADER, traceId);
    registerAsyncTraceContext(request, traceId);
    String previousTraceId = MDC.get(RequestTraceId.MDC_KEY);
    MDC.put(RequestTraceId.MDC_KEY, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (previousTraceId == null) {
        MDC.remove(RequestTraceId.MDC_KEY);
      } else {
        MDC.put(RequestTraceId.MDC_KEY, previousTraceId);
      }
    }
  }

  private static void registerAsyncTraceContext(HttpServletRequest request, String traceId) {
    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
    if (asyncManager.getCallableInterceptor(CALLABLE_INTERCEPTOR_KEY) == null) {
      asyncManager.registerCallableInterceptor(
          CALLABLE_INTERCEPTOR_KEY, new TraceCallableInterceptor(traceId));
    }
  }

  private abstract static class TraceAsyncInterceptor {

    private final String traceId;
    private final ThreadLocal<PreviousTrace> previousTrace = new ThreadLocal<>();

    private TraceAsyncInterceptor(String traceId) {
      this.traceId = traceId;
    }

    final void open() {
      previousTrace.set(new PreviousTrace(MDC.get(RequestTraceId.MDC_KEY)));
      MDC.put(RequestTraceId.MDC_KEY, traceId);
    }

    final void close() {
      PreviousTrace previous = previousTrace.get();
      if (previous == null) {
        return;
      }
      if (previous.value() == null) {
        MDC.remove(RequestTraceId.MDC_KEY);
      } else {
        MDC.put(RequestTraceId.MDC_KEY, previous.value());
      }
      previousTrace.remove();
    }
  }

  private static final class TraceCallableInterceptor extends TraceAsyncInterceptor
      implements CallableProcessingInterceptor {

    private TraceCallableInterceptor(String traceId) {
      super(traceId);
    }

    @Override
    public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
      open();
    }

    @Override
    public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
      close();
    }

    @Override
    public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) {
      close();
    }
  }

  private record PreviousTrace(String value) {}
}
