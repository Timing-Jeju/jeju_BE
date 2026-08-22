package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalDistributedFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightPolicy;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStatus;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcTagoArrivalFlightStoreIntegrationTest {
  private static final Duration LEASE = Duration.ofSeconds(12);
  private static final Duration RETAIN = Duration.ofSeconds(25);
  private static final Duration QUARANTINE = Duration.ofSeconds(12);
  private static final String FINGERPRINT = "a".repeat(64);

  @Autowired private JdbcTagoArrivalFlightStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private HikariDataSource primaryDataSource;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("delete from public.tago_arrival_flights");
  }

  @Test
  void 실제_PostgreSQL_동시20_claim은_generation_owner_leader가_정확히1개다() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var requests =
          IntStream.range(0, 20)
              .mapToObj(
                  index ->
                      executor.submit(
                          () ->
                              store.observeOrClaim(
                                  FINGERPRINT, new UUID(39L, index + 1L), LEASE, QUARANTINE)))
              .toList();
      List<TagoArrivalFlightDecision> decisions =
          requests.stream().map(request -> get(request)).toList();

      assertThat(decisions)
          .filteredOn(decision -> decision.status() == TagoArrivalFlightStatus.LEADER)
          .hasSize(1);
      assertThat(decisions)
          .allSatisfy(decision -> assertThat(decision.lease().generation()).isOne());
    }
  }

  @Test
  void TIMEOUT_RATE_LIMIT_EMPTY_terminal은_retain동안_exact_outcome으로_replay된다() {
    for (TagoArrivalException.Code code :
        List.of(
            TagoArrivalException.Code.TIMEOUT,
            TagoArrivalException.Code.RATE_LIMITED,
            TagoArrivalException.Code.EMPTY_RESULT)) {
      jdbc.update("delete from public.tago_arrival_flights");
      TagoArrivalFlightDecision leader =
          store.observeOrClaim(FINGERPRINT, new UUID(39L, 1L), LEASE, QUARANTINE);

      assertThat(store.completeFailure(leader.lease(), code, RETAIN)).isTrue();
      TagoArrivalFlightDecision replay =
          store.observeOrClaim(FINGERPRINT, new UUID(39L, 2L), LEASE, QUARANTINE);

      assertThat(replay.status()).isEqualTo(TagoArrivalFlightStatus.FAILED);
      assertThat(replay.outcome()).contains(code);
    }
  }

  @Test
  void expired_RUNNING은_즉시_steal하지_않고_ABANDON후_quarantine뒤_generation을_증가시킨다() {
    TagoArrivalFlightDecision first =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 1L), LEASE, QUARANTINE);
    jdbc.update(
        """
        update public.tago_arrival_flights
        set updated_at=statement_timestamp() - interval '2 seconds',
            lease_expires_at=statement_timestamp() - interval '1 second'
        where fingerprint=?
        """,
        FINGERPRINT);

    TagoArrivalFlightDecision abandoned =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 2L), LEASE, QUARANTINE);
    assertThat(abandoned.status()).isEqualTo(TagoArrivalFlightStatus.ABANDONED);
    assertThat(store.completeSuccess(first.lease(), RETAIN)).isFalse();

    jdbc.update(
        """
        update public.tago_arrival_flights
        set updated_at=statement_timestamp() - interval '2 seconds',
            retain_until=statement_timestamp() - interval '1 second'
        where fingerprint=?
        """,
        FINGERPRINT);
    TagoArrivalFlightDecision next =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 3L), LEASE, QUARANTINE);
    assertThat(next.status()).isEqualTo(TagoArrivalFlightStatus.LEADER);
    assertThat(next.lease().generation()).isEqualTo(2);
  }

  @Test
  void main_pool_size2여도_서로다른20_stop_callback은_connection을_점유하지_않고_모두_진입한다() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(primaryDataSource.getJdbcUrl());
    config.setUsername(primaryDataSource.getUsername());
    config.setPassword(primaryDataSource.getPassword());
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    try (HikariDataSource pool = new HikariDataSource(config)) {
      JdbcTagoArrivalFlightStore smallPoolStore =
          new JdbcTagoArrivalFlightStore(new JdbcTemplate(pool));
      TagoArrivalDistributedFlightCoordinator coordinator =
          new TagoArrivalDistributedFlightCoordinator(
              smallPoolStore,
              new TagoArrivalFlightPolicy(
                  Duration.ofSeconds(10),
                  Duration.ofMillis(10),
                  Duration.ofSeconds(8),
                  LEASE,
                  RETAIN,
                  QUARANTINE));
      CountDownLatch entered = new CountDownLatch(20);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger actions = new AtomicInteger();

      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var requests =
            IntStream.range(0, 20)
                .mapToObj(
                    index ->
                        executor.submit(
                            () ->
                                coordinator.coalesce(
                                    TagoArrivalCacheKey.tago(
                                        new UUID(39L, index + 100L), "39", "NODE-" + index),
                                    () -> {
                                      actions.incrementAndGet();
                                      entered.countDown();
                                      await(release);
                                      return snapshot(index);
                                    })))
                .toList();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        release.countDown();
        for (var request : requests) assertThat(get(request)).isNotNull();
      }
      assertThat(actions).hasValue(20);
    }
  }

  private static TagoArrivalSnapshot snapshot(int index) {
    Instant observedAt = Instant.parse("2026-08-21T00:00:00Z");
    return new TagoArrivalSnapshot(
        List.of(new TagoArrival("ROUTE-" + index, "201", null, null, 60, 1)),
        observedAt,
        observedAt.plusSeconds(25),
        false,
        new UUID(39L, index + 1000L),
        new UUID(39L, index + 2000L));
  }

  private static <T> T get(java.util.concurrent.Future<T> future) {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
