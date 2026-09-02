package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.global.logging.RequestTraceId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;

class McpPrivateRequestFilterTest {

  @AfterEach
  void MDC를_정리한다() {
    MDC.clear();
  }

  @Test
  void 매_MCP_HTTP_요청은_새_service_JWT와_서버_traceId만_전파한다() {
    McpServiceJwtIssuer jwtIssuer = mock(McpServiceJwtIssuer.class);
    when(jwtIssuer.issue()).thenReturn("first-token", "second-token");
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    ExchangeFunction exchange =
        request -> {
          captured.set(request);
          return reactor.core.publisher.Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
    var filter = McpPrivateRequestFilter.create(jwtIssuer);
    ClientRequest request =
        ClientRequest.create(
                org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://timing-jeju-ai:8000/mcp"))
            .build();

    MDC.put(RequestTraceId.MDC_KEY, "0123456789abcdef0123456789abcdef");
    filter.filter(request, exchange).block();
    assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer first-token");
    assertThat(captured.get().headers().getFirst(RequestTraceId.TRACE_ID_HEADER))
        .isEqualTo("0123456789abcdef0123456789abcdef");

    MDC.put(RequestTraceId.MDC_KEY, "not-a-canonical-trace");
    filter.filter(request, exchange).block();
    assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer second-token");
    assertThat(captured.get().headers().getFirst(RequestTraceId.TRACE_ID_HEADER)).isNull();
  }
}
