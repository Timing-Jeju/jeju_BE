package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdkExternalHttpTransportTest {

  @Test
  void 실제_transport는_redirect를_따라가지_않고_stream으로_body를_반환한다() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          byte[] body = "followed".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();

    try {
      JdkExternalHttpTransport transport =
          new JdkExternalHttpTransport(
              provider ->
                  HttpClient.newBuilder()
                      .connectTimeout(Duration.ofSeconds(2))
                      .followRedirects(HttpClient.Redirect.NEVER)
                      .build());
      ExternalHttpRequest request =
          new ExternalHttpRequest(
              ExternalApiProvider.TOUR_API,
              ExternalApiHttpMethod.GET,
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect"),
              Map.of());

      try (ExternalHttpResponse response =
          transport.exchange(request, Duration.ofSeconds(2), Duration.ofSeconds(5))) {
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.headers()).containsKey("location");
        assertThat(response.body().readAllBytes()).isEmpty();
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void 실제_transport는_응답_대기_timeout을_예외로_반환한다() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(300);
            exchange.sendResponseHeaders(200, 0);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      JdkExternalHttpTransport transport = new JdkExternalHttpTransport();
      ExternalHttpRequest request =
          new ExternalHttpRequest(
              ExternalApiProvider.TOUR_API,
              ExternalApiHttpMethod.GET,
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"),
              Map.of());

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> transport.exchange(request, Duration.ofSeconds(2), Duration.ofMillis(50)))
          .isInstanceOf(java.net.http.HttpTimeoutException.class);
    } finally {
      server.stop(0);
    }
  }
}
