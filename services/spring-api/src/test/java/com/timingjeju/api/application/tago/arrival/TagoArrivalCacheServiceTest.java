package com.timingjeju.api.application.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoArrivalCacheServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");

  @Test
  void 같은_stop의_동시_20요청은_하나의_loader_future를_공유한다() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              return snapshot(clock.instant());
            },
            clock,
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(ignored -> executor.submit(() -> service.get(KEY)))
              .toList();
      entered.await();
      release.countDown();
      for (var future : futures) {
        assertThat(future.get()).isEqualTo(snapshot(NOW));
      }
    }

    assertThat(calls).hasValue(1);
    assertThat(service.inFlightCount()).isZero();
  }

  @Test
  void fresh는_원래_expiry를_사용하고_90초_provider장애는_같은_observation을_stale로_반환한다() {
    MutableClock clock = new MutableClock(NOW);
    AtomicInteger calls = new AtomicInteger();
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              if (calls.getAndIncrement() == 0) return snapshot(clock.instant());
              throw TagoArrivalException.timeout();
            },
            clock,
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    TagoArrivalSnapshot initial = service.get(KEY);
    clock.advance(Duration.ofSeconds(24));
    assertThat(service.get(KEY)).isEqualTo(initial);
    assertThat(calls).hasValue(1);

    clock.advance(Duration.ofSeconds(66));
    TagoArrivalSnapshot fallback = service.get(KEY);
    assertThat(fallback.stale()).isTrue();
    assertThat(fallback.observedAt()).isEqualTo(NOW);
    assertThat(fallback.expiresAt()).isEqualTo(NOW.plusSeconds(25));
    assertThat(calls).hasValue(2);
  }

  @Test
  void stale는_정확히_2분까지만_허용하고_실패_future는_정리되어_재시도된다() {
    MutableClock clock = new MutableClock(NOW);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<TagoArrivalException> failure =
        new AtomicReference<>(TagoArrivalException.timeout());
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              if (calls.getAndIncrement() == 0) return snapshot(NOW);
              throw failure.get();
            },
            clock,
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));
    service.get(KEY);

    clock.advance(Duration.ofMinutes(2));
    assertThat(service.get(KEY).stale()).isTrue();
    assertThat(service.inFlightCount()).isZero();

    clock.advance(Duration.ofMillis(1));
    assertThatThrownBy(() -> service.get(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            thrown -> assertThat(thrown.code()).isEqualTo(TagoArrivalException.Code.TIMEOUT));
    assertThat(service.inFlightCount()).isZero();
    assertThat(calls).hasValue(3);

    assertThat(service.cleanup()).isEqualTo(1);
    assertThat(service.cachedStopCount()).isZero();
  }

  @Test
  void 재시작후_DB의_90초_snapshot도_provider_timeout에서_stale로_반환하고_새_write를_만들지_않는다() {
    MutableClock clock = new MutableClock(NOW.plusSeconds(90));
    AtomicInteger loads = new AtomicInteger();
    TagoArrivalSnapshot persisted = snapshot(NOW);
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              loads.incrementAndGet();
              throw TagoArrivalException.timeout();
            },
            key -> java.util.Optional.of(persisted),
            clock,
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    TagoArrivalSnapshot result = service.get(KEY);

    assertThat(result.stale()).isTrue();
    assertThat(result.observedAt()).isEqualTo(NOW);
    assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(25));
    assertThat(result.importRunId()).isEqualTo(persisted.importRunId());
    assertThat(loads).hasValue(1);
  }

  @Test
  void 서로_다른_두_instance의_동시_20요청도_distributed_lock후_history를_재확인해_loader는_한번이다() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    AtomicInteger loads = new AtomicInteger();
    AtomicReference<TagoArrivalSnapshot> persisted = new AtomicReference<>();
    SharedCoordinator coordinator = new SharedCoordinator();
    TagoArrivalLoader loader =
        key -> {
          loads.incrementAndGet();
          TagoArrivalSnapshot loaded = snapshot(clock.instant());
          persisted.set(loaded);
          return loaded;
        };
    TagoArrivalHistory history = key -> Optional.ofNullable(persisted.get());
    List<TagoArrivalCacheService> instances =
        List.of(
            new TagoArrivalCacheService(
                loader, history, coordinator, clock, Duration.ofSeconds(25), Duration.ofMinutes(2)),
            new TagoArrivalCacheService(
                loader,
                history,
                coordinator,
                clock,
                Duration.ofSeconds(25),
                Duration.ofMinutes(2)));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(
                  index -> executor.submit(() -> instances.get(index % instances.size()).get(KEY)))
              .toList();
      for (var future : futures) assertThat(future.get()).isEqualTo(snapshot(NOW));
    }

    assertThat(loads).hasValue(1);
    assertThat(instances).allSatisfy(instance -> assertThat(instance.inFlightCount()).isZero());
  }

  @Test
  void 요청시작_119초였어도_timeout완료가_121초면_stale을_거부한다() {
    MutableClock clock = new MutableClock(NOW.plusSeconds(119));
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              clock.advance(Duration.ofSeconds(2));
              throw TagoArrivalException.timeout();
            },
            key -> Optional.of(snapshot(NOW)),
            new SharedCoordinator(),
            clock,
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    assertThatThrownBy(() -> service.get(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.TIMEOUT));
  }

  @Test
  void 공식_EMPTY_RESULT는_90초_history가_있어도_stale로_대체하지_않는다() {
    TagoArrivalCacheService service =
        serviceWithFailure(TagoArrivalException.emptyResult(), NOW.plusSeconds(90));

    assertThatThrownBy(() -> service.get(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.EMPTY_RESULT));
  }

  @Test
  void timeout_rateLimit_providerUnavailable만_120초이하_stale_fallback을_허용한다() {
    for (TagoArrivalException failure :
        List.of(
            TagoArrivalException.timeout(),
            TagoArrivalException.rateLimited(),
            TagoArrivalException.providerUnavailable())) {
      assertThat(serviceWithFailure(failure, NOW.plusSeconds(90)).get(KEY).stale()).isTrue();
    }
  }

  @Test
  void history_DATA_UNAVAILABLE는_stable_code만_노출하고_provider를_호출하지_않는다() {
    AtomicInteger loads = new AtomicInteger();
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              loads.incrementAndGet();
              return snapshot(NOW);
            },
            key -> {
              throw TagoArrivalException.dataUnavailable();
            },
            new SharedCoordinator(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    assertThatThrownBy(() -> service.get(KEY))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
            });
    assertThat(loads).hasValue(0);
  }

  @Test
  void loader의_programmer_bug는_PROVIDER_UNAVAILABLE로_숨기지_않는다() {
    IllegalStateException programmerBug = new IllegalStateException("serialization bug");
    TagoArrivalCacheService service =
        new TagoArrivalCacheService(
            key -> {
              throw programmerBug;
            },
            key -> Optional.of(snapshot(NOW)),
            new SharedCoordinator(),
            Clock.fixed(NOW.plusSeconds(90), ZoneOffset.UTC),
            Duration.ofSeconds(25),
            Duration.ofMinutes(2));

    assertThatThrownBy(() -> service.get(KEY)).isSameAs(programmerBug);
  }

  private static TagoArrivalCacheService serviceWithFailure(
      TagoArrivalException failure, Instant now) {
    return new TagoArrivalCacheService(
        key -> {
          throw failure;
        },
        key -> Optional.of(snapshot(NOW)),
        new SharedCoordinator(),
        Clock.fixed(now, ZoneOffset.UTC),
        Duration.ofSeconds(25),
        Duration.ofMinutes(2));
  }

  private static TagoArrivalSnapshot snapshot(Instant observedAt) {
    return new TagoArrivalSnapshot(
        List.of(new TagoArrival("JER001", "201", "간선버스", "일반차량", 321, 4)),
        observedAt,
        observedAt.plusSeconds(25),
        false,
        UUID.fromString("39000000-0000-0000-0000-000000000002"),
        UUID.fromString("39000000-0000-0000-0000-000000000003"));
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class SharedCoordinator implements TagoArrivalFlightCoordinator {
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public TagoArrivalSnapshot coalesce(
        TagoArrivalCacheKey key, Supplier<TagoArrivalSnapshot> action) {
      lock.lock();
      try {
        return action.get();
      } finally {
        lock.unlock();
      }
    }
  }
}
