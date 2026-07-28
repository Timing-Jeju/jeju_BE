package com.timingjeju.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
class NaverUserInfoHttpGatewayTest {

  private HttpServer server;
  private ExecutorService serverExecutor;
  private NaverUserInfoHttpGateway gateway;
  private final AtomicReference<Scenario> scenario = new AtomicReference<>();
  private final AtomicReference<String> authorizationHeader = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/v1/nid/me",
        exchange -> {
          authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          scenario.get().respond(exchange);
        });
    server.start();
    gateway =
        NaverUserInfoHttpGateway.forTest(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            new ObjectMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/nid/me"));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    serverExecutor.shutdownNow();
  }

  @Test
  void 고정_endpoint에만_Bearer_token을_전달하고_정상_응답을_파싱한다() {
    scenario.set(
        Scenario.response(
            200, "{\"response\":{\"id\":\"naver-id\",\"email\":\"user@example.test\"}}"));

    Map<String, Object> response = gateway.getUserInfo("opaque-provider-token");

    assertThat(authorizationHeader.get()).isEqualTo("Bearer opaque-provider-token");
    assertThat(response).containsKey("response");
  }

  @Test
  void Naver_401_403_429_5xx를_안전한_분류로_변환한다() {
    assertFailure(401, "UPSTREAM_UNAUTHORIZED");
    assertFailure(403, "UPSTREAM_FORBIDDEN");
    assertFailure(429, "UPSTREAM_RATE_LIMITED");
    assertFailure(500, "UPSTREAM_UNAVAILABLE");
  }

  @Test
  void malformed_및_oversized_body는_안전하게_거부한다() {
    scenario.set(Scenario.response(200, "not-json"));
    assertThatThrownBy(() -> gateway.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("UPSTREAM_MALFORMED_RESPONSE");

    scenario.set(Scenario.response(200, "x".repeat(64 * 1024 + 1)));
    assertThatThrownBy(() -> gateway.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("UPSTREAM_RESPONSE_TOO_LARGE");
  }

  @Test
  void timeout은_원본_예외나_토큰을_노출하지_않는다() {
    scenario.set(Scenario.timeout());

    assertThatThrownBy(() -> gateway.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo("UPSTREAM_TIMEOUT");
  }

  @Test
  void 응답_header와_첫_byte_후_body가_멈춰도_전체_deadline에_취소되고_bulkhead가_회복한다() throws Exception {
    CountDownLatch firstByteSent = new CountDownLatch(1);
    CountDownLatch clientClosed = new CountDownLatch(1);
    scenario.set(Scenario.stalledBody(firstByteSent, clientClosed));
    NaverUserInfoAdmissionService admission =
        NaverUserInfoAdmissionService.forTest(100, Duration.ofSeconds(10), 1, System::nanoTime);

    long startedAt = System.nanoTime();
    assertThatThrownBy(
            () ->
                admission.execute(
                    () -> gateway.getUserInfo("opaque-provider-token-that-must-not-leak")))
        .isInstanceOf(NaverUserInfoException.class)
        .satisfies(
            exception -> {
              NaverUserInfoException failure = (NaverUserInfoException) exception;
              assertThat(failure.code().name()).isEqualTo("UPSTREAM_TIMEOUT");
              assertThat(failure.getMessage())
                  .doesNotContain("opaque-provider-token-that-must-not-leak")
                  .doesNotContain("stalled-body-secret");
            });
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertThat(firstByteSent.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(elapsedMillis).isBetween(2500L, 4500L);
    assertThat(clientClosed.await(1, TimeUnit.SECONDS)).isTrue();

    scenario.set(Scenario.response(200, "{\"resultcode\":\"00\",\"message\":\"success\"}"));
    assertThat(admission.execute(() -> gateway.getUserInfo("recovered-provider-token")))
        .containsEntry("resultcode", "00");
  }

  @Test
  void deadline을_위한_별도_executor를_소유하지_않아_shutdown_누락을_만들지_않는다() {
    assertThat(Arrays.stream(NaverUserInfoHttpGateway.class.getDeclaredFields()))
        .noneMatch(field -> Executor.class.isAssignableFrom(field.getType()));
  }

  private void assertFailure(int status, String expectedCode) {
    scenario.set(Scenario.response(status, "ignored"));
    assertThatThrownBy(() -> gateway.getUserInfo("opaque-provider-token"))
        .isInstanceOf(NaverUserInfoException.class)
        .extracting(exception -> ((NaverUserInfoException) exception).code().name())
        .isEqualTo(expectedCode);
  }

  private interface Scenario {

    void respond(HttpExchange exchange) throws IOException;

    static Scenario response(int status, String body) {
      return exchange -> {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
      };
    }

    static Scenario timeout() {
      return exchange -> {
        try {
          Thread.sleep(3500);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
        exchange.close();
      };
    }

    static Scenario stalledBody(CountDownLatch firstByteSent, CountDownLatch clientClosed) {
      return exchange -> {
        exchange.sendResponseHeaders(200, 0);
        try {
          exchange.getResponseBody().write('{');
          exchange.getResponseBody().flush();
          firstByteSent.countDown();
          for (int attempt = 0; attempt < 50; attempt++) {
            try {
              Thread.sleep(100);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              return;
            }
            exchange.getResponseBody().write(' ');
            exchange.getResponseBody().flush();
          }
          exchange.getResponseBody().write("}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException exception) {
          clientClosed.countDown();
        } finally {
          exchange.close();
        }
      };
    }
  }
}
