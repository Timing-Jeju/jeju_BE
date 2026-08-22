package com.timingjeju.api.application.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TagoArrivalDistributedFlightCoordinatorTest {
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");
  private static final TagoArrivalFlightPolicy POLICY =
      new TagoArrivalFlightPolicy(
          Duration.ofSeconds(10),
          Duration.ofMillis(1),
          Duration.ofSeconds(8),
          Duration.ofSeconds(12),
          Duration.ofSeconds(25),
          Duration.ofSeconds(12));

  @Test
  void 두_instance의_동시20요청은_TIMEOUT_RATE_LIMIT_EMPTY각각_provider1회와_exact_outcome을_공유한다()
      throws Exception {
    for (TagoArrivalException failure :
        List.of(
            TagoArrivalException.timeout(),
            TagoArrivalException.rateLimited(),
            TagoArrivalException.emptyResult())) {
      SharedStore store = new SharedStore();
      AtomicInteger providers = new AtomicInteger();
      List<TagoArrivalFlightCoordinator> coordinators =
          List.of(coordinator(store), coordinator(store));
      CountDownLatch ready = new CountDownLatch(20);
      CountDownLatch start = new CountDownLatch(1);

      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var requests =
            java.util.stream.IntStream.range(0, 20)
                .mapToObj(
                    index ->
                        executor.submit(
                            () -> {
                              ready.countDown();
                              await(start);
                              try {
                                coordinators
                                    .get(index % coordinators.size())
                                    .coalesce(
                                        KEY,
                                        () -> {
                                          providers.incrementAndGet();
                                          throw failure;
                                        });
                                return null;
                              } catch (TagoArrivalException observed) {
                                return observed.code();
                              }
                            }))
                .toList();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (var request : requests) {
          assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(failure.code());
        }
      }

      assertThat(providers).hasValue(1);
    }
  }

  @Test
  void claim_SQL이_deadline을_넘겨_LEADER를_반환해도_action은_0이고_abandon한다() {
    AtomicLong nanos = new AtomicLong();
    AtomicInteger actions = new AtomicInteger();
    TagoArrivalFlightStore delayedStore =
        new SharedStore() {
          @Override
          public synchronized TagoArrivalFlightDecision observeOrClaim(
              String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
            nanos.addAndGet(POLICY.deadline().toNanos());
            return super.observeOrClaim(fingerprint, proposedOwner, lease, quarantine);
          }
        };
    TagoArrivalDistributedFlightCoordinator coordinator =
        coordinator(delayedStore, nanos, ignored -> {});

    assertDataUnavailable(
        () ->
            coordinator.coalesce(
                KEY,
                () -> {
                  actions.incrementAndGet();
                  return snapshot();
                }));

    assertThat(actions).hasValue(0);
    assertThat(((SharedStore) delayedStore).abandoned).isTrue();
  }

  @Test
  void claim이_반환한_lease를_action에_전달한다() {
    SharedStore store = new SharedStore();
    AtomicReference<TagoArrivalFlightLease> observed = new AtomicReference<>();

    coordinator(store)
        .coalesce(
            KEY,
            lease -> {
              observed.set(lease);
              return snapshot();
            },
            TagoArrivalDistributedFlightCoordinatorTest::snapshot);

    assertThat(observed.get()).isEqualTo(store.state.lease());
    assertThat(store.sourceExpiresAt).isEqualTo(snapshot().expiresAt());
  }

  @Test
  void claim_반환직후_interrupt면_action0_leader_abandon하고_interrupt를_보존한다() {
    AtomicInteger actions = new AtomicInteger();
    SharedStore store =
        new SharedStore() {
          @Override
          public synchronized TagoArrivalFlightDecision observeOrClaim(
              String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
            TagoArrivalFlightDecision decision =
                super.observeOrClaim(fingerprint, proposedOwner, lease, quarantine);
            Thread.currentThread().interrupt();
            return decision;
          }
        };
    try {
      assertDataUnavailable(
          () ->
              coordinator(store)
                  .coalesce(
                      KEY,
                      lease -> {
                        actions.incrementAndGet();
                        return snapshot();
                      },
                      TagoArrivalDistributedFlightCoordinatorTest::snapshot));
      assertThat(actions).hasValue(0);
      assertThat(store.abandoned).isTrue();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void programmer_bug는_leader에_원형전파하고_follower에는_stable_DATA_UNAVAILABLE다() {
    SharedStore store = new SharedStore();
    IllegalStateException programmerBug = new IllegalStateException("serializer bug");
    TagoArrivalFlightCoordinator leader = coordinator(store);
    TagoArrivalFlightCoordinator follower = coordinator(store);

    assertThatThrownBy(
            () ->
                leader.coalesce(
                    KEY,
                    () -> {
                      throw programmerBug;
                    }))
        .isSameAs(programmerBug);
    assertDataUnavailable(
        () -> follower.coalesce(KEY, TagoArrivalDistributedFlightCoordinatorTest::snapshot));
  }

  @Test
  void SUCCEEDED_replay가_source_expired면_bounded_reobserve후_새_generation을_claim한다() {
    AtomicInteger observes = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    TagoArrivalFlightLease old = new TagoArrivalFlightLease("a".repeat(64), 1, new UUID(0, 10));
    TagoArrivalFlightStore store =
        new SharedStore() {
          @Override
          public synchronized TagoArrivalFlightDecision observeOrClaim(
              String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
            if (observes.getAndIncrement() == 0) {
              return TagoArrivalFlightDecision.succeeded(old);
            }
            return TagoArrivalFlightDecision.leader(fingerprint, 2, proposedOwner);
          }
        };

    TagoArrivalSnapshot result =
        coordinator(store)
            .coalesce(
                KEY,
                lease -> {
                  actions.incrementAndGet();
                  assertThat(lease.generation()).isEqualTo(2);
                  return snapshot();
                },
                () -> {
                  throw TagoArrivalReplayExpiredException.create();
                });

    assertThat(result).isEqualTo(snapshot());
    assertThat(observes).hasValue(2);
    assertThat(actions).hasValue(1);
  }

  @Test
  void lease_retain_quarantine은_provider_hard_timeout보다_커야한다() {
    assertThatThrownBy(
            () ->
                new TagoArrivalFlightPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofMillis(1),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(25),
                    Duration.ofSeconds(12)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TagoArrivalFlightPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofMillis(1),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(12),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(12)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TagoArrivalFlightPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofMillis(1),
                    Duration.ofSeconds(8),
                    Duration.ofSeconds(12),
                    Duration.ofSeconds(25),
                    Duration.ofSeconds(8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TagoArrivalDistributedFlightCoordinator coordinator(TagoArrivalFlightStore store) {
    AtomicLong nanos = new AtomicLong();
    return coordinator(store, nanos, nanos::addAndGet);
  }

  private static TagoArrivalDistributedFlightCoordinator coordinator(
      TagoArrivalFlightStore store, AtomicLong nanos, java.util.function.LongConsumer pause) {
    AtomicLong owners = new AtomicLong();
    return new TagoArrivalDistributedFlightCoordinator(
        store, nanos::get, pause, () -> new UUID(0L, owners.incrementAndGet()), POLICY);
  }

  private static void assertDataUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getCause()).isNull();
            });
  }

  private static TagoArrivalSnapshot snapshot() {
    Instant observedAt = Instant.parse("2026-08-21T00:00:00Z");
    return new TagoArrivalSnapshot(
        List.of(new TagoArrival("JER001", "201", "간선", "일반", 60, 1)),
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
      throw new AssertionError(interrupted);
    }
  }

  private static class SharedStore implements TagoArrivalFlightStore {
    private TagoArrivalFlightDecision state;
    private boolean abandoned;
    private Instant sourceExpiresAt;

    @Override
    public synchronized TagoArrivalFlightDecision observeOrClaim(
        String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
      if (state == null) {
        state = TagoArrivalFlightDecision.leader(fingerprint, 1, proposedOwner);
        return state;
      }
      return state.status() == TagoArrivalFlightStatus.LEADER
          ? TagoArrivalFlightDecision.running(state.lease())
          : state;
    }

    @Override
    public synchronized boolean completeSuccess(TagoArrivalFlightLease lease, Duration retain) {
      state = TagoArrivalFlightDecision.succeeded(lease);
      return true;
    }

    @Override
    public synchronized boolean completeSuccess(
        TagoArrivalFlightLease lease, Instant expiresAt, Duration retain) {
      sourceExpiresAt = expiresAt;
      return completeSuccess(lease, retain);
    }

    @Override
    public synchronized boolean completeFailure(
        TagoArrivalFlightLease lease, TagoArrivalException.Code code, Duration retain) {
      state = TagoArrivalFlightDecision.failed(lease, code);
      return true;
    }

    @Override
    public synchronized boolean abandon(TagoArrivalFlightLease lease, Duration quarantine) {
      abandoned = true;
      state = TagoArrivalFlightDecision.failed(lease, TagoArrivalException.Code.DATA_UNAVAILABLE);
      return true;
    }
  }
}
