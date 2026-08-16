package com.timingjeju.api.application.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
}
