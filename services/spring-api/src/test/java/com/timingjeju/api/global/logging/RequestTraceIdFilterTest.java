package com.timingjeju.api.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("unit")
class RequestTraceIdFilterTest {

  @Test
  void 서버가_요청마다_traceId를_생성해_attribute_header_MDC로_전파하고_정리한다() throws Exception {
    AtomicInteger sequence = new AtomicInteger();
    RequestTraceId traceId =
        new RequestTraceId(() -> "%032x".formatted(sequence.incrementAndGet()));
    RequestTraceIdFilter filter = new RequestTraceIdFilter(traceId);
    MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/first");
    firstRequest.addHeader(RequestTraceId.TRACE_ID_HEADER, "client-controlled-value");
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    FilterChain firstChain =
        (request, response) -> {
          assertThat(request.getAttribute(RequestTraceId.TRACE_ID_ATTRIBUTE))
              .isEqualTo("00000000000000000000000000000001");
          assertThat(MDC.get(RequestTraceId.MDC_KEY)).isEqualTo("00000000000000000000000000000001");
        };

    MDC.put(RequestTraceId.MDC_KEY, "upstream-trace");
    filter.doFilter(firstRequest, firstResponse, firstChain);

    assertThat(firstResponse.getHeader(RequestTraceId.TRACE_ID_HEADER))
        .isEqualTo("00000000000000000000000000000001");
    assertThat(MDC.get(RequestTraceId.MDC_KEY)).isEqualTo("upstream-trace");
    MDC.remove(RequestTraceId.MDC_KEY);

    MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/second");
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    filter.doFilter(secondRequest, secondResponse, (request, response) -> {});

    assertThat(secondResponse.getHeader(RequestTraceId.TRACE_ID_HEADER))
        .isEqualTo("00000000000000000000000000000002");
  }
}
