package com.timingjeju.api.application.mobility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class MobilityRouteCacheServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
  private static final MobilityRouteRequest WALK_REQUEST =
      new MobilityRouteRequest(
          new MobilityPoint(33.5067, 126.4930),
          new MobilityPoint(33.5104, 126.4913),
          MobilityMode.WALK,
          Instant.parse("2026-09-02T09:00:00Z"));

  @Test
  void 동일_request_hash의_fresh_route는_provider를_다시_호출하지_않는다() {
    AtomicInteger calls = new AtomicInteger();
    MobilityRouteProvider provider =
        provider(
            "official.walking",
            request -> {
              calls.incrementAndGet();
              return walkMeasurement(Duration.ofMinutes(5));
            });
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider, request -> walkMeasurement(Duration.ofMinutes(1)), fixedClock(NOW));

    MobilityRouteFact first = service.get(WALK_REQUEST);
    MobilityRouteFact second = service.get(WALK_REQUEST);

    assertThat(second).isSameAs(first);
    assertThat(calls).hasValue(1);
    assertThat(first.requestHash()).matches("[0-9a-f]{64}");
    assertThat(first.sourceId()).isEqualTo("official.walking");
    assertThat(first.durationMinutes()).isEqualTo(25);
    assertThat(first.observedAt()).isEqualTo(NOW);
    assertThat(first.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(first.reason()).isEqualTo(MobilityRouteReason.PROVIDER_FACT);
    assertThat(first.estimated()).isFalse();
    assertThat(first.stale()).isFalse();
  }

  @Test
  void expiry와_현재시각이_같으면_stale_route를_반환하지_않고_다시_조회한다() {
    MutableClock clock = new MutableClock(NOW);
    AtomicInteger calls = new AtomicInteger();
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider(
                "official.walking",
                request -> {
                  calls.incrementAndGet();
                  return walkMeasurement(Duration.ofMinutes(5));
                }),
            request -> walkMeasurement(Duration.ofMinutes(1)),
            clock);

    MobilityRouteFact first = service.get(WALK_REQUEST);
    clock.advance(Duration.ofMinutes(5));
    MobilityRouteFact second = service.get(WALK_REQUEST);

    assertThat(calls).hasValue(2);
    assertThat(second).isNotSameAs(first);
    assertThat(second.observedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
  }

  @Test
  void 복구가능한_보행_provider_실패만_보수추정_reason으로_대체한다() {
    AtomicInteger estimates = new AtomicInteger();
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider(
                "official.walking",
                request -> {
                  throw new RuntimeException("provider-raw-secret");
                }),
            request -> {
              estimates.incrementAndGet();
              return walkMeasurement(Duration.ofMinutes(1));
            },
            fixedClock(NOW));

    MobilityRouteFact result = service.get(WALK_REQUEST);

    assertThat(estimates).hasValue(1);
    assertThat(result.reason()).isEqualTo(MobilityRouteReason.ESTIMATED_WALK_TIME);
    assertThat(result.sourceId()).isEqualTo("conservative-walk-policy");
    assertThat(result.estimated()).isTrue();
    assertThat(result.toString()).doesNotContain("provider-raw-secret");
  }

  @Test
  void 차량_provider_실패는_추정하지_않고_원문없는_안정_code로_거부한다() {
    MobilityRouteRequest drivingRequest =
        new MobilityRouteRequest(
            WALK_REQUEST.origin(),
            WALK_REQUEST.destination(),
            MobilityMode.RENTAL_CAR,
            WALK_REQUEST.departureAt());
    AtomicInteger estimates = new AtomicInteger();
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider(
                "official.driving",
                request -> {
                  throw new RuntimeException("provider-raw-secret");
                }),
            request -> {
              estimates.incrementAndGet();
              return walkMeasurement(Duration.ofMinutes(1));
            },
            fixedClock(NOW));

    assertThatThrownBy(() -> service.get(drivingRequest))
        .isInstanceOfSatisfying(
            MobilityRouteException.class,
            failure -> {
              assertThat(failure.code())
                  .isEqualTo(MobilityRouteException.Code.PROVIDER_UNAVAILABLE);
              assertThat(failure).hasMessage("PROVIDER_UNAVAILABLE").hasNoCause();
            });
    assertThat(estimates).hasValue(0);
  }

  @Test
  void provider가_다른_mode를_반환하면_보행_fallback없이_잘못된_응답으로_거부한다() {
    AtomicInteger estimates = new AtomicInteger();
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider(
                "official.walking",
                request ->
                    new MobilityRouteMeasurement(
                        MobilityMode.RENTAL_CAR,
                        1_200,
                        new MobilityDurationComponents(0, 0, 5, 0, 0),
                        null,
                        Duration.ofMinutes(5))),
            request -> {
              estimates.incrementAndGet();
              return walkMeasurement(Duration.ofMinutes(1));
            },
            fixedClock(NOW));

    assertThatThrownBy(() -> service.get(WALK_REQUEST))
        .isInstanceOfSatisfying(
            MobilityRouteException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo(MobilityRouteException.Code.INVALID_PROVIDER_RESPONSE));
    assertThat(estimates).hasValue(0);
  }

  @Test
  void provider가_null을_반환하면_보행_fallback없이_잘못된_응답으로_거부한다() {
    AtomicInteger estimates = new AtomicInteger();
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider("official.walking", request -> null),
            request -> {
              estimates.incrementAndGet();
              return walkMeasurement(Duration.ofMinutes(1));
            },
            fixedClock(NOW));

    assertThatThrownBy(() -> service.get(WALK_REQUEST))
        .isInstanceOfSatisfying(
            MobilityRouteException.class,
            failure ->
                assertThat(failure.code())
                    .isEqualTo(MobilityRouteException.Code.INVALID_PROVIDER_RESPONSE));
    assertThat(estimates).hasValue(0);
  }

  @Test
  void 만료_cache_cleanup은_expiry_동등경계를_포함하고_fresh는_보존한다() {
    MutableClock clock = new MutableClock(NOW);
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider("official.walking", request -> walkMeasurement(Duration.ofMinutes(5))),
            request -> walkMeasurement(Duration.ofMinutes(1)),
            clock);
    service.get(WALK_REQUEST);

    clock.advance(Duration.ofMinutes(4).plusSeconds(59));
    assertThat(service.cleanup()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(service.cleanup()).isEqualTo(1);
  }

  @Test
  void 동일_hash의_동시_20요청은_하나의_provider_future를_공유한다() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    MobilityRouteCacheService service =
        new MobilityRouteCacheService(
            provider(
                "official.walking",
                request -> {
                  calls.incrementAndGet();
                  entered.countDown();
                  await(release);
                  return walkMeasurement(Duration.ofMinutes(5));
                }),
            request -> walkMeasurement(Duration.ofMinutes(1)),
            fixedClock(NOW));

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new ConcurrentLinkedQueue<MobilityRouteFact>();
      var futures =
          java.util.stream.IntStream.range(0, 20)
              .mapToObj(ignored -> executor.submit(() -> results.add(service.get(WALK_REQUEST))))
              .toList();
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
      assertThat(results)
          .hasSize(20)
          .allSatisfy(result -> assertThat(result).isSameAs(results.peek()));
    }

    assertThat(calls).hasValue(1);
    assertThat(service.inFlightCount()).isZero();
  }

  private static MobilityRouteProvider provider(
      String sourceId,
      java.util.function.Function<MobilityRouteRequest, MobilityRouteMeasurement> fetch) {
    return new MobilityRouteProvider() {
      @Override
      public String sourceId() {
        return sourceId;
      }

      @Override
      public MobilityRouteMeasurement fetch(MobilityRouteRequest request) {
        return fetch.apply(request);
      }
    };
  }

  private static MobilityRouteMeasurement walkMeasurement(Duration validFor) {
    return new MobilityRouteMeasurement(
        MobilityMode.WALK, 1_200, new MobilityDurationComponents(2, 0, 20, 0, 3), null, validFor);
  }

  private static Clock fixedClock(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted");
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
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
      return current;
    }
  }
}
