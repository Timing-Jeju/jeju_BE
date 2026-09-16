package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class JdkSupabaseAdminHttpTransportDeadlineTest {

  @Test
  void response_header만_도착하고_body가_stall해도_deadline에_exchange를_cancel한다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.bodyStall("/body-stall");
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofMillis(100));
      HttpRequest request = request(server.uri("/body-stall"), Duration.ofMillis(100));

      long started = System.nanoTime();
      assertNetworkFailure(() -> transport.exchange(request, new byte[0], 64));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

      server.release();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  @Test
  void response_body_size_cap을_넘으면_stream을_cancel하고_retryable로_정규화한다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.respond("/too-large", "12345".getBytes());
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofSeconds(1));

      assertThatThrownBy(
              () ->
                  transport.exchange(
                      request(server.uri("/too-large"), Duration.ofSeconds(1)), new byte[0], 4))
          .isInstanceOfSatisfying(
              DeletionOperationException.class,
              failure -> assertThat(failure.isRetryable()).isTrue())
          .hasMessage("SUPABASE_RESPONSE_TOO_LARGE")
          .hasNoCause();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  @Test
  void slow_body와_header_stall도_전체_response_deadline안에_중단된다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.headerStall("/header-stall");
      server.slowBody("/slow-body");
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofMillis(120));

      assertNetworkFailure(
          () ->
              transport.exchange(
                  request(server.uri("/header-stall"), Duration.ofMillis(120)), new byte[0], 64));
      assertNetworkFailure(
          () ->
              transport.exchange(
                  request(server.uri("/slow-body"), Duration.ofMillis(120)), new byte[0], 64));

      server.release();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  @Test
  void interrupt는_underlying_exchange를_cancel하고_calling_thread를_회복시킨다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.bodyStall("/interrupt");
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofSeconds(5));
      AtomicInteger retryableFailures = new AtomicInteger();
      Thread caller =
          Thread.ofPlatform()
              .unstarted(
                  () -> {
                    try {
                      transport.exchange(
                          request(server.uri("/interrupt"), Duration.ofSeconds(5)),
                          new byte[0],
                          64);
                    } catch (DeletionOperationException failure) {
                      if (failure.isRetryable() && Thread.currentThread().isInterrupted()) {
                        retryableFailures.incrementAndGet();
                      }
                    }
                  });
      caller.start();
      assertThat(server.awaitRequest()).isTrue();

      caller.interrupt();
      caller.join(500);

      assertThat(caller.isAlive()).isFalse();
      assertThat(retryableFailures).hasValue(1);
      server.release();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  @Test
  void in_flight_lease_checkpoint가_상실되면_body_exchange를_cancel한다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.bodyStall("/lease-loss");
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofSeconds(5));
      AtomicInteger checkpoints = new AtomicInteger();

      assertThatThrownBy(
              () ->
                  transport.exchange(
                      request(server.uri("/lease-loss"), Duration.ofSeconds(5)),
                      new byte[0],
                      64,
                      () -> {
                        checkpoints.incrementAndGet();
                        throw DeletionOperationException.retryable("LEASE_LOST_DURING_HTTP");
                      }))
          .isInstanceOf(DeletionOperationException.class)
          .hasMessage("LEASE_LOST_DURING_HTTP");
      assertThat(checkpoints).hasValue(1);
      server.release();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  @Test
  void stalled_Storage_list_timeout뒤_delete_batch와_Auth_delete를_호출하지_않는다() throws Exception {
    try (StallingServer server = new StallingServer()) {
      server.bodyStall("/storage/v1/object/list/profile-images");
      server.count("/storage/v1/object/profile-images", server.storageDeletes);
      server.count("/auth/v1/admin/users/46d9a0ca-3472-4f7e-b1b8-b751da5a7199", server.authDeletes);
      SupabaseAdminSettings settings = settings(server.baseUri(), Duration.ofMillis(100));
      JdkSupabaseAdminHttpTransport transport = transport(Duration.ofMillis(100));
      SupabaseProfileImageDeletionHttpGateway storage =
          new SupabaseProfileImageDeletionHttpGateway(settings, new ObjectMapper(), transport);
      SupabaseAuthAdminHttpGateway auth = new SupabaseAuthAdminHttpGateway(settings, transport);
      AuthSubject subject = AuthSubject.of("46d9a0ca-3472-4f7e-b1b8-b751da5a7199");

      assertNetworkFailure(
          () -> {
            storage.deletePrefix(subject.profileImagePrefix(), () -> {});
            auth.deleteUser(subject);
          });

      assertThat(server.storageDeletes).hasValue(0);
      assertThat(server.authDeletes).hasValue(0);
      server.release();
      assertThat(server.awaitIdle()).isTrue();
    }
  }

  private static JdkSupabaseAdminHttpTransport transport(Duration timeout) {
    return new JdkSupabaseAdminHttpTransport(
        HttpClient.newBuilder().connectTimeout(timeout).build(), timeout);
  }

  private static HttpRequest request(URI uri, Duration timeout) {
    return HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
  }

  private static SupabaseAdminSettings settings(URI uri, Duration timeout) {
    return SupabaseAdminSettings.enabled(uri, "test-service-role", timeout, timeout, 10, 10);
  }

  private static void assertNetworkFailure(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            DeletionOperationException.class, failure -> assertThat(failure.isRetryable()).isTrue())
        .hasMessage("SUPABASE_NETWORK_FAILURE")
        .hasNoCause();
  }

  private static final class StallingServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch request = new CountDownLatch(1);
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger storageDeletes = new AtomicInteger();
    private final AtomicInteger authDeletes = new AtomicInteger();

    private StallingServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.start();
    }

    private URI baseUri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI uri(String path) {
      return baseUri().resolve(path);
    }

    private void bodyStall(String path) {
      server.createContext(
          path,
          exchange ->
              handle(
                  exchange,
                  () -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('[');
                    exchange.getResponseBody().flush();
                    request.countDown();
                    awaitRelease();
                    exchange.getResponseBody().write(']');
                  }));
    }

    private void headerStall(String path) {
      server.createContext(
          path,
          exchange ->
              handle(
                  exchange,
                  () -> {
                    request.countDown();
                    awaitRelease();
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("ok".getBytes());
                  }));
    }

    private void slowBody(String path) {
      server.createContext(
          path,
          exchange ->
              handle(
                  exchange,
                  () -> {
                    exchange.sendResponseHeaders(200, 0);
                    for (int index = 0; index < 10; index++) {
                      exchange.getResponseBody().write('a');
                      exchange.getResponseBody().flush();
                      Thread.sleep(60);
                    }
                  }));
    }

    private void count(String path, AtomicInteger counter) {
      server.createContext(
          path,
          exchange ->
              handle(
                  exchange,
                  () -> {
                    counter.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                  }));
    }

    private void respond(String path, byte[] body) {
      server.createContext(
          path,
          exchange ->
              handle(
                  exchange,
                  () -> {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                  }));
    }

    private void handle(HttpExchange exchange, ThrowingRunnable action) {
      active.incrementAndGet();
      try (exchange) {
        action.run();
      } catch (Exception ignored) {
        // Client cancellation is observed as a closed exchange by the local fixture.
      } finally {
        active.decrementAndGet();
      }
    }

    private boolean awaitRequest() throws InterruptedException {
      return request.await(2, TimeUnit.SECONDS);
    }

    private void awaitRelease() throws InterruptedException {
      release.await(2, TimeUnit.SECONDS);
    }

    private void release() {
      release.countDown();
    }

    private boolean awaitIdle() throws InterruptedException {
      for (int attempt = 0; attempt < 100; attempt++) {
        if (active.get() == 0) {
          return true;
        }
        Thread.sleep(10);
      }
      return false;
    }

    @Override
    public void close() {
      release();
      server.stop(0);
      executor.shutdownNow();
      try {
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
          throw new AssertionError("local HTTP fixture tasks did not terminate");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while closing local HTTP fixture", failure);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
