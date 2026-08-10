package com.timingjeju.api.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IdempotencyServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000017");
  private static final String KEY = "018f6f2a-60a0-7f5b-8c61-8f548f34bc31";

  @Test
  void 같은_scope와_hash는_operation을_한번만_실행하고_status_header_body를_재생한다() {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    AtomicInteger executions = new AtomicInteger();
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{\"name\":\"제주\"}");
    IdempotencyResponse original = response(201, "{\"tripId\":\"17\"}");

    IdempotencyResponse first =
        service.execute(
            request,
            () -> {
              executions.incrementAndGet();
              return original;
            });
    IdempotencyResponse replay =
        service.execute(
            request,
            () -> {
              throw new AssertionError("replay에서 operation을 실행하면 안 됩니다.");
            });

    assertThat(executions).hasValue(1);
    assertThat(replay.status()).isEqualTo(first.status());
    assertThat(replay.headers()).containsExactlyElementsOf(first.headers());
    assertThat(replay.body()).containsExactly(first.body());
  }

  @Test
  void 같은_scope의_다른_body는_reused로_거부하고_최초_응답을_보존한다() {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    IdempotencyRequest first = request(OWNER, "/api/v1/trips", "{\"name\":\"제주\"}");
    IdempotencyRequest changed = request(OWNER, "/api/v1/trips", "{\"name\":\"서귀포\"}");
    IdempotencyResponse original = response(201, "first");
    service.execute(first, () -> original);

    assertThatThrownBy(() -> service.execute(changed, () -> response(201, "second")))
        .isInstanceOf(IdempotencyException.class)
        .extracting("code")
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(service.execute(first, () -> response(500, "wrong")).body())
        .containsExactly(original.body());
  }

  @Test
  void 처리중인_동시_loser는_retry_after_1과_409를_받고_operation을_실행하지_않는다() throws Exception {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    CountDownLatch operationStarted = new CountDownLatch(1);
    CountDownLatch finishOperation = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{}");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var winner =
          executor.submit(
              () ->
                  service.execute(
                      request,
                      () -> {
                        executions.incrementAndGet();
                        operationStarted.countDown();
                        await(finishOperation);
                        return response(201, "winner");
                      }));
      assertThat(operationStarted.await(2, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> service.execute(request, () -> response(201, "loser")))
          .isInstanceOf(IdempotencyException.class)
          .satisfies(
              error -> {
                IdempotencyException conflict = (IdempotencyException) error;
                assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                assertThat(conflict.status()).isEqualTo(409);
                assertThat(conflict.retryAfterSeconds()).hasValue(1);
              });
      finishOperation.countDown();
      assertThat(winner.get(2, TimeUnit.SECONDS).body()).isEqualTo(bytes("winner"));
    }
    assertThat(executions).hasValue(1);
  }

  @Test
  void 예상하지_못한_예외는_marker와_업무_변경을_rollback하고_재시도를_허용한다() {
    InMemoryStore store = new InMemoryStore();
    RecordingTransactions transactions = new RecordingTransactions();
    IdempotencyUseCase service = service(store, transactions);
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{}");

    assertThatThrownBy(
            () ->
                service.execute(
                    request,
                    () -> {
                      transactions.businessChanges.add("partial");
                      throw new IllegalStateException("unexpected");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(transactions.businessChanges).isEmpty();
    assertThat(service.execute(request, () -> response(201, "retried")).body())
        .isEqualTo(bytes("retried"));
  }

  @Test
  void 반환된_5xx는_cache하지_않고_다음_요청이_다시_실행한다() {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{}");
    AtomicInteger executions = new AtomicInteger();

    IdempotencyResponse failure =
        service.execute(
            request,
            () -> {
              executions.incrementAndGet();
              return response(503, "temporary");
            });
    IdempotencyResponse success =
        service.execute(
            request,
            () -> {
              executions.incrementAndGet();
              return response(201, "success");
            });

    assertThat(failure.status()).isEqualTo(503);
    assertThat(success.status()).isEqualTo(201);
    assertThat(executions).hasValue(2);
  }

  @Test
  void owner와_path가_다르면_같은_key를_독립적으로_사용한다() {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    UUID otherOwner = UUID.fromString("20000000-0000-0000-0000-000000000017");

    IdempotencyResponse ownerResult =
        service.execute(request(OWNER, "/api/v1/trips", "{}"), () -> response(201, "owner"));
    IdempotencyResponse otherOwnerResult =
        service.execute(request(otherOwner, "/api/v1/trips", "{}"), () -> response(201, "other"));
    IdempotencyResponse otherPathResult =
        service.execute(request(OWNER, "/api/v1/runs", "{}"), () -> response(202, "path"));

    assertThat(ownerResult.body()).isEqualTo(bytes("owner"));
    assertThat(otherOwnerResult.body()).isEqualTo(bytes("other"));
    assertThat(otherPathResult.body()).isEqualTo(bytes("path"));
  }

  @Test
  void 완료_TTL_24시간과_processing_lease_2분의_직전과_경계를_구분한다() {
    InMemoryStore store = new InMemoryStore();
    MutableClock clock = new MutableClock(NOW);
    IdempotencyUseCase service =
        new TransactionalIdempotencyService(store, new DirectTransactions(), clock);
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{}");
    service.execute(request, () -> response(201, "old"));

    clock.advance(Duration.ofHours(24).minusNanos(1));
    assertThat(service.execute(request, () -> response(201, "too-early")).body())
        .isEqualTo(bytes("old"));
    clock.advance(Duration.ofNanos(1));
    assertThat(service.execute(request, () -> response(201, "new")).body()).isEqualTo(bytes("new"));

    IdempotencyRequest processing = request(OWNER, "/api/v1/runs", "{}");
    store.acquire(processing.scope(), processing.requestHash(), clock.instant());
    clock.advance(Duration.ofMinutes(2).minusNanos(1));
    assertThatThrownBy(() -> service.execute(processing, () -> response(202, "too-early")))
        .isInstanceOf(IdempotencyException.class);
    clock.advance(Duration.ofNanos(1));
    assertThat(service.execute(processing, () -> response(202, "leased")).body())
        .isEqualTo(bytes("leased"));
  }

  @Test
  void response_body가_1MiB를_넘으면_완료하지_않고_재시도를_허용한다() {
    InMemoryStore store = new InMemoryStore();
    IdempotencyUseCase service = service(store);
    IdempotencyRequest request = request(OWNER, "/api/v1/trips", "{}");

    assertThatThrownBy(
            () ->
                service.execute(
                    request, () -> new IdempotencyResponse(201, List.of(), new byte[1_048_577])))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 MiB");
    assertThat(service.execute(request, () -> response(201, "retry")).body())
        .isEqualTo(bytes("retry"));
  }

  private static IdempotencyUseCase service(InMemoryStore store) {
    return service(store, new DirectTransactions());
  }

  private static IdempotencyUseCase service(
      InMemoryStore store, IdempotencyTransactions transactions) {
    return new TransactionalIdempotencyService(
        store, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static IdempotencyRequest request(UUID owner, String path, String body) {
    return IdempotencyRequest.create(owner, "POST", path, KEY, bytes(body));
  }

  private static IdempotencyResponse response(int status, String body) {
    return new IdempotencyResponse(
        status, List.of(new IdempotencyHeader("Content-Type", "application/json")), bytes(body));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timeout");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static class DirectTransactions implements IdempotencyTransactions {
    @Override
    public <T> T requiresNew(java.util.function.Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      return work.get();
    }
  }

  private static final class RecordingTransactions extends DirectTransactions {
    private final List<String> businessChanges = new ArrayList<>();

    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      List<String> before = List.copyOf(businessChanges);
      try {
        return work.get();
      } catch (RuntimeException exception) {
        businessChanges.clear();
        businessChanges.addAll(before);
        throw exception;
      }
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private static final class InMemoryStore implements IdempotencyRecordStore {
    private final Map<IdempotencyScope, Entry> entries = new java.util.HashMap<>();

    @Override
    public synchronized IdempotencyAcquisition acquire(
        IdempotencyScope scope, String requestHash, Instant now) {
      Entry current = entries.get(scope);
      if (current == null || !now.isBefore(current.deadline)) {
        entries.put(scope, Entry.processing(requestHash, now.plus(Duration.ofMinutes(2))));
        return IdempotencyAcquisition.acquired(UUID.randomUUID());
      }
      if (!current.requestHash.equals(requestHash)) {
        return IdempotencyAcquisition.reused();
      }
      if (current.response == null) {
        return IdempotencyAcquisition.processing();
      }
      return IdempotencyAcquisition.replay(current.response);
    }

    @Override
    public synchronized void complete(
        IdempotencyScope scope,
        String requestHash,
        UUID attemptToken,
        IdempotencyResponse response,
        Instant now) {
      entries.put(scope, Entry.completed(requestHash, response, now.plus(Duration.ofHours(24))));
    }

    @Override
    public synchronized void release(
        IdempotencyScope scope, String requestHash, UUID attemptToken) {
      entries.computeIfPresent(
          scope, (ignored, entry) -> entry.requestHash.equals(requestHash) ? null : entry);
    }

    private record Entry(String requestHash, IdempotencyResponse response, Instant deadline) {
      static Entry processing(String hash, Instant deadline) {
        return new Entry(hash, null, deadline);
      }

      static Entry completed(String hash, IdempotencyResponse response, Instant deadline) {
        return new Entry(hash, response, deadline);
      }
    }
  }
}
