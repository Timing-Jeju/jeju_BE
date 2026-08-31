package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalDistributedFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightPolicy;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStatus;
import com.timingjeju.api.application.tago.arrival.TagoArrivalLoadService;
import com.timingjeju.api.application.tago.arrival.TagoArrivalProcessResult;
import com.timingjeju.api.application.tago.arrival.TagoArrivalProcessor;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSourceResponse;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
@ExtendWith(OutputCaptureExtension.class)
class JdbcTagoArrivalFlightStoreIntegrationTest {
  private static final Duration LEASE = Duration.ofSeconds(12);
  private static final Duration RETAIN = Duration.ofSeconds(25);
  private static final Duration QUARANTINE = Duration.ofSeconds(12);
  private static final Duration FOLLOWER_BATCH_DEADLINE = Duration.ofSeconds(3);
  private static final String FINGERPRINT = "a".repeat(64);

  @Autowired private JdbcTagoArrivalFlightStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private HikariDataSource primaryDataSource;
  @Autowired private TransactionTemplate transactions;

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
    Instant databaseNow = databaseNow();
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
                                      return snapshot(index, databaseNow);
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

  @Test
  void success_retain은_source_expiry를_넘지않고_이미만료된_source는_publish하지않는다() {
    TagoArrivalFlightDecision leader =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 1L), LEASE, QUARANTINE);
    Instant sourceExpiresAt = databaseNow().plusSeconds(2).plusNanos(854);
    Instant expectedRetainedUntil = sourceExpiresAt.truncatedTo(ChronoUnit.MICROS);

    assertThat(store.completeSuccess(leader.lease(), sourceExpiresAt, RETAIN)).isTrue();
    Instant retainedUntil =
        jdbc.queryForObject(
                "select retain_until from public.tago_arrival_flights where fingerprint=?",
                java.sql.Timestamp.class,
                FINGERPRINT)
            .toInstant();
    assertThat(retainedUntil).isEqualTo(expectedRetainedUntil).isBeforeOrEqualTo(sourceExpiresAt);

    jdbc.update("delete from public.tago_arrival_flights");
    TagoArrivalFlightDecision expired =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 2L), LEASE, QUARANTINE);
    assertThat(store.completeSuccess(expired.lease(), databaseNow().minusSeconds(1), RETAIN))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select state from public.tago_arrival_flights where fingerprint=?",
                String.class,
                FINGERPRINT))
        .isEqualTo("running");
  }

  @Test
  void expired_terminal_cleanup은_current_retained_RUNNING을_보존하고_batch32와_index를_쓴다() {
    jdbc.update(
        """
        insert into public.tago_arrival_flights (
          fingerprint,generation,owner_token,lease_expires_at,state,outcome_code,
          retain_until,updated_at
        )
        select lpad(to_hex(value),64,'0'),1,gen_random_uuid(),clock_timestamp()-interval '2 seconds',
               'failed','timeout',clock_timestamp()-interval '1 second',
               clock_timestamp()-interval '3 seconds'
        from generate_series(1,100) value
        """);
    TagoArrivalFlightDecision current =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 3L), LEASE, QUARANTINE);
    String retained = "b".repeat(64);
    jdbc.update(
        """
        insert into public.tago_arrival_flights (
          fingerprint,generation,owner_token,lease_expires_at,state,outcome_code,
          retain_until,updated_at
        ) values (?,1,gen_random_uuid(),clock_timestamp()+interval '12 seconds',
                  'failed','timeout',clock_timestamp()+interval '25 seconds',clock_timestamp())
        """,
        retained);

    assertThat(store.cleanupExpiredTerminals(current.lease().fingerprint(), 32)).isEqualTo(32);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tago_arrival_flights where state <> 'running' and retain_until <= clock_timestamp()",
                Integer.class))
        .isEqualTo(36);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.tago_arrival_flights where fingerprint in (?,?)",
                Integer.class,
                FINGERPRINT,
                retained))
        .isEqualTo(2);

    jdbc.execute("set enable_seqscan=off");
    try {
      String plan =
          String.join(
              "\n",
              jdbc.queryForList(
                  """
                  explain (costs off)
                  select fingerprint from public.tago_arrival_flights
                  where state <> 'running' and retain_until <= clock_timestamp()
                  order by retain_until,fingerprint limit 32
                  """,
                  String.class));
      assertThat(plan).contains("idx_tago_arrival_flights_cleanup");
    } finally {
      jdbc.execute("reset enable_seqscan");
    }
  }

  @Test
  void source_fetch_callback동안_main_Hikari_active_connection은_0이다() {
    AtomicInteger activeDuringFetch = new AtomicInteger(-1);
    TagoArrivalSnapshot expected = snapshot(39);
    TagoArrivalProcessor processor =
        new TagoArrivalProcessor() {
          @Override
          public TagoArrivalProcessResult process(
              com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease flight,
              TagoArrivalCacheKey key,
              TagoArrivalSourceResponse response,
              Instant observedAt,
              Instant expiresAt) {
            return TagoArrivalProcessResult.success(expected);
          }

          @Override
          public TagoArrivalException.Code recordTransportFailure(
              com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease flight,
              TagoArrivalCacheKey key,
              Instant observedAt,
              TagoArrivalException.Code code) {
            throw new AssertionError("success fixture");
          }
        };
    TagoArrivalLoadService loader =
        new TagoArrivalLoadService(
            (city, node) -> {
              activeDuringFetch.set(primaryDataSource.getHikariPoolMXBean().getActiveConnections());
              return new TagoArrivalSourceResponse("{}".getBytes(), SnapshotPayloadFormat.JSON);
            },
            processor,
            Clock.fixed(expected.observedAt(), ZoneOffset.UTC),
            Duration.ofSeconds(25));

    assertThat(
            loader.load(
                TagoArrivalCacheKey.tago(new UUID(39, 39), "39", "NODE-39"),
                new com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease(
                    "e".repeat(64), 1, new UUID(39, 40))))
        .isEqualTo(expected);
    assertThat(activeDuringFetch).hasValue(0);
  }

  @Test
  void processor_row_lock중_same_fingerprint_followers는_pool을_막지않고_deadline내_action0이다(
      CapturedOutput output) throws Exception {
    TagoArrivalCacheKey key = TagoArrivalCacheKey.tago(new UUID(39L, 500L), "39", "LOCKED-NODE");
    String lockedFingerprint = fingerprint(key);
    TagoArrivalFlightDecision leader =
        store.observeOrClaim(lockedFingerprint, new UUID(39L, 1L), LEASE, QUARANTINE);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(primaryDataSource.getJdbcUrl());
    config.setUsername(primaryDataSource.getUsername());
    config.setPassword(primaryDataSource.getPassword());
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);

    try (HikariDataSource pool = new HikariDataSource(config)) {
      warmFollowerPoolWithDatabaseTimeouts(pool);
      assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(2);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

      var executor = Executors.newVirtualThreadPerTaskExecutor();
      CountDownLatch observersReady = new CountDownLatch(20);
      CountDownLatch startObservers = new CountDownLatch(1);
      AtomicInteger observersCompleted = new AtomicInteger();
      AtomicInteger actions = new AtomicInteger();
      Future<?> lockHolder = null;
      try {
        lockHolder =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        ignored -> {
                          store.lockCurrent(leader.lease());
                          locked.countDown();
                          await(release);
                        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        JdbcTagoArrivalFlightStore followerStore =
            new JdbcTagoArrivalFlightStore(new JdbcTemplate(pool));
        List<FollowerTask> observers =
            IntStream.range(0, 20)
                .mapToObj(
                    index -> {
                      String label = "follower-%02d".formatted(index);
                      return new FollowerTask(
                          label,
                          executor.submit(
                              () ->
                                  observeFollower(
                                      label,
                                      index,
                                      observersReady,
                                      startObservers,
                                      observersCompleted,
                                      followerStore,
                                      lockedFingerprint)));
                    })
                .toList();

        assertThat(observersReady.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(observersCompleted).hasValue(0);
        long batchDeadlineNanos = System.nanoTime() + FOLLOWER_BATCH_DEADLINE.toNanos();
        startObservers.countDown();
        List<FollowerObservation> observations =
            observers.stream()
                .map(
                    observer ->
                        getBeforeDeadline(observer.label(), observer.future(), batchDeadlineNanos))
                .toList();

        assertThat(observations).hasSize(20);
        for (FollowerObservation observation : observations) {
          assertThat(observation.decision().status())
              .describedAs(observation.label())
              .isEqualTo(TagoArrivalFlightStatus.RUNNING);
          assertThat(observation.completedAtNanos())
              .describedAs(observation.label())
              .isLessThanOrEqualTo(batchDeadlineNanos);
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

        TagoArrivalDistributedFlightCoordinator coordinator =
            new TagoArrivalDistributedFlightCoordinator(
                followerStore,
                new TagoArrivalFlightPolicy(
                    Duration.ofMillis(250),
                    Duration.ofMillis(20),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)));
        long startedAt = System.nanoTime();
        try {
          coordinator.coalesce(
              key,
              () -> {
                actions.incrementAndGet();
                return snapshot(500, databaseNow());
              });
          throw new AssertionError("deadline 뒤 DATA_UNAVAILABLE이어야 합니다.");
        } catch (TagoArrivalException failure) {
          assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
            .isBetween(Duration.ofMillis(200), Duration.ofSeconds(1));
        assertThat(actions).hasValue(0);
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      } finally {
        startObservers.countDown();
        release.countDown();
        if (lockHolder != null) lockHolder.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      transactions.executeWithoutResult(ignored -> store.lockCurrent(leader.lease()));
      assertThat(
              jdbc.queryForObject(
                  "select generation from public.tago_arrival_flights where fingerprint=?",
                  Long.class,
                  lockedFingerprint))
          .isEqualTo(leader.lease().generation());
      assertThat(
              containsAnySensitiveText(
                  output.getAll(),
                  primaryDataSource.getPassword(),
                  lockedFingerprint,
                  "LOCKED-NODE"))
          .describedAs("raw credential/location log count")
          .isFalse();
    }
  }

  @Test
  void follower_batch는_observer별_relative_timeout이_아닌하나의_absolute_deadline을_쓴다() {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch beginDelayedRelease = new CountDownLatch(1);
      CountDownLatch releaseSecond = new CountDownLatch(1);
      Future<FollowerObservation> second =
          executor.submit(
              () -> {
                await(releaseSecond);
                return new FollowerObservation(
                    "deadline-second", TagoArrivalFlightDecision.contended(), System.nanoTime());
              });
      Future<?> delayedRelease =
          executor.submit(
              () -> {
                await(beginDelayedRelease);
                try {
                  Thread.sleep(Duration.ofSeconds(1));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
                releaseSecond.countDown();
              });
      long batchDeadlineNanos = System.nanoTime() + Duration.ofMillis(250).toNanos();

      beginDelayedRelease.countDown();
      assertThatThrownBy(() -> getBeforeDeadline("deadline-second", second, batchDeadlineNanos))
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("absolute deadline");
      releaseSecond.countDown();
      get(delayedRelease);
    }
  }

  @Test
  void follower_failure와_unfinished_deadline은_observer_label과_stage를_보존한다() {
    CompletableFuture<FollowerObservation> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("database observe failed"));

    assertThatThrownBy(
            () -> getBeforeDeadline("follower-07", failed, System.nanoTime() + 1_000_000_000L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("follower-07")
        .hasMessageContaining("DB observe");

    assertThatThrownBy(
            () ->
                getBeforeDeadline(
                    "expired-reclaim-follower-07",
                    "coalescing after generation-2 leader",
                    failed,
                    System.nanoTime() + 1_000_000_000L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expired-reclaim-follower-07")
        .hasMessageContaining("coalescing after generation-2 leader");

    CompletableFuture<FollowerObservation> unfinished = new CompletableFuture<>();
    assertThatThrownBy(
            () -> getBeforeDeadline("follower-11", unfinished, System.nanoTime() + 20_000_000L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("follower-11")
        .hasMessageContaining("absolute deadline");

    assertThatThrownBy(() -> getBeforeDeadline("follower-13", unfinished, System.nanoTime() - 1L))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("follower-13")
        .hasMessageContaining("absolute deadline");
  }

  @Test
  void follower는_coordinated_start가_release되기전에는_barrier를_통과하지않는다() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch escaped = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> follower =
          executor.submit(
              () -> {
                awaitCoordinatedStart(ready, start);
                escaped.countDown();
              });

      assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(escaped.await(100, TimeUnit.MILLISECONDS)).isFalse();
      start.countDown();
      follower.get(1, TimeUnit.SECONDS);
      assertThat(escaped.getCount()).isZero();
    } finally {
      start.countDown();
    }
  }

  @Test
  void expired_FAILED_ABANDONED_row_lock중_direct_observe는_CONTENDED이고_old_outcome은_없다()
      throws Exception {
    for (TagoArrivalFlightStatus terminal :
        List.of(TagoArrivalFlightStatus.FAILED, TagoArrivalFlightStatus.ABANDONED)) {
      clean();
      ExpiredReclaimFixture fixture = seedExpiredTerminal(terminal);
      String actor = terminal + "-locked-observer";
      long outerDeadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();

      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> lockHolder =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        ignored -> {
                          jdbc.queryForObject(
                              "select generation from public.tago_arrival_flights where fingerprint=? for update",
                              Long.class,
                              fixture.fingerprint());
                          locked.countDown();
                          await(release);
                        }));
        awaitBeforeDeadline(actor, "row lock acquisition", locked, outerDeadlineNanos);
        TagoArrivalFlightDecision observed =
            store.observeOrClaim(fixture.fingerprint(), new UUID(39L, 2L), LEASE, QUARANTINE);

        assertThat(observed.status())
            .describedAs(actor)
            .isEqualTo(TagoArrivalFlightStatus.CONTENDED);
        assertThat(observed.outcome()).describedAs(actor + " old outcome").isEmpty();
        try {
          assertThat(locked.getCount()).describedAs(actor + " lock held").isZero();
        } finally {
          release.countDown();
        }
        getBeforeDeadline(actor, "row lock release", lockHolder, outerDeadlineNanos);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void expired_FAILED_ABANDONED_lock해제후_generation2_LEADER확정뒤_followers는_provider1_same_outcome이다()
      throws Exception {
    for (TagoArrivalFlightStatus terminal :
        List.of(TagoArrivalFlightStatus.FAILED, TagoArrivalFlightStatus.ABANDONED)) {
      clean();
      ExpiredReclaimFixture fixture = seedExpiredTerminal(terminal);
      String actor = terminal + "-generation-2-leader";
      long outerDeadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);

      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> lockHolder =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        ignored -> {
                          jdbc.queryForObject(
                              "select generation from public.tago_arrival_flights where fingerprint=? for update",
                              Long.class,
                              fixture.fingerprint());
                          locked.countDown();
                          await(release);
                        }));
        awaitBeforeDeadline(actor, "row lock acquisition", locked, outerDeadlineNanos);
        TagoArrivalFlightDecision contended =
            store.observeOrClaim(fixture.fingerprint(), new UUID(39L, 2L), LEASE, QUARANTINE);
        assertThat(contended.status()).isEqualTo(TagoArrivalFlightStatus.CONTENDED);
        assertThat(contended.outcome()).isEmpty();

        release.countDown();
        getBeforeDeadline(actor, "row lock release", lockHolder, outerDeadlineNanos);
        TagoArrivalFlightDecision reclaimed =
            store.observeOrClaim(fixture.fingerprint(), new UUID(39L, 3L), LEASE, QUARANTINE);

        assertThat(reclaimed.status()).describedAs(actor).isEqualTo(TagoArrivalFlightStatus.LEADER);
        assertThat(reclaimed.lease().generation()).describedAs(actor).isEqualTo(2L);
        assertThat(reclaimed.outcome()).describedAs(actor + " cleared outcome").isEmpty();

        int followerCount = 4;
        CountDownLatch followersReady = new CountDownLatch(followerCount);
        CountDownLatch startFollowers = new CountDownLatch(1);
        CountDownLatch followersObservedLeader = new CountDownLatch(followerCount);
        AtomicInteger providers = new AtomicInteger();
        TagoArrivalSnapshot fresh = snapshot(terminal.ordinal() + 600, databaseNow());
        List<LabeledSnapshotTask> followers =
            IntStream.range(0, followerCount)
                .mapToObj(
                    index -> {
                      String followerActor = terminal + "-follower-%02d".formatted(index);
                      JdbcTagoArrivalFlightStore followerStore =
                          new RunningStageReportingStore(jdbc, followersObservedLeader);
                      return new LabeledSnapshotTask(
                          followerActor,
                          executor.submit(
                              () -> {
                                awaitCoordinatedStart(followersReady, startFollowers);
                                return new TagoArrivalDistributedFlightCoordinator(
                                        followerStore,
                                        new TagoArrivalFlightPolicy(
                                            Duration.ofSeconds(5),
                                            Duration.ofMillis(10),
                                            Duration.ofSeconds(1),
                                            LEASE,
                                            RETAIN,
                                            QUARANTINE))
                                    .coalesce(
                                        fixture.key(),
                                        ignored -> {
                                          providers.incrementAndGet();
                                          return fresh;
                                        },
                                        () -> fresh);
                              }));
                    })
                .toList();

        try {
          awaitBeforeDeadline(actor, "followers ready", followersReady, outerDeadlineNanos);
          startFollowers.countDown();
          awaitBeforeDeadline(
              actor,
              "followers observed generation-2 leader",
              followersObservedLeader,
              outerDeadlineNanos);
          providers.incrementAndGet();
          assertThat(store.completeSuccess(reclaimed.lease(), fresh.expiresAt(), RETAIN)).isTrue();

          for (LabeledSnapshotTask follower : followers) {
            assertThat(
                    getBeforeDeadline(
                        follower.actor(),
                        "coalescing after generation-2 leader",
                        follower.future(),
                        outerDeadlineNanos))
                .isEqualTo(fresh);
          }
        } finally {
          startFollowers.countDown();
        }
        assertThat(providers).describedAs(actor).hasValue(1);
      } finally {
        release.countDown();
      }
    }
  }

  private ExpiredReclaimFixture seedExpiredTerminal(TagoArrivalFlightStatus terminal) {
    TagoArrivalCacheKey key =
        TagoArrivalCacheKey.tago(
            new UUID(39L, terminal == TagoArrivalFlightStatus.FAILED ? 600L : 601L),
            "39",
            "RECLAIM-" + terminal);
    String lockedFingerprint = fingerprint(key);
    TagoArrivalFlightDecision first =
        store.observeOrClaim(lockedFingerprint, new UUID(39L, 1L), LEASE, QUARANTINE);
    if (terminal == TagoArrivalFlightStatus.FAILED) {
      assertThat(
              store.completeFailure(first.lease(), TagoArrivalException.Code.EMPTY_RESULT, RETAIN))
          .isTrue();
    } else {
      assertThat(store.abandon(first.lease(), QUARANTINE)).isTrue();
    }
    jdbc.update(
        """
        update public.tago_arrival_flights
        set updated_at=clock_timestamp()-interval '2 seconds',
            retain_until=clock_timestamp()-interval '1 second'
        where fingerprint=?
        """,
        lockedFingerprint);
    return new ExpiredReclaimFixture(key, lockedFingerprint);
  }

  private static TagoArrivalSnapshot snapshot(int index) {
    Instant observedAt = Instant.parse("2026-08-21T00:00:00Z");
    return snapshot(index, observedAt);
  }

  private static TagoArrivalSnapshot snapshot(int index, Instant observedAt) {
    return new TagoArrivalSnapshot(
        List.of(new TagoArrival("ROUTE-" + index, "201", null, null, 60, 1)),
        observedAt,
        observedAt.plusSeconds(25),
        false,
        new UUID(39L, index + 1000L),
        new UUID(39L, index + 2000L));
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
  }

  private static String fingerprint(TagoArrivalCacheKey key) {
    String canonical =
        component(key.provider())
            + component(key.service())
            + component(key.cityCode())
            + component(key.stopId().toString())
            + component(key.nodeId());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String component(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return bytes.length + ":" + value;
  }

  private static <T> T get(java.util.concurrent.Future<T> future) {
    try {
      return future.get(15, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void warmFollowerPoolWithDatabaseTimeouts(HikariDataSource pool)
      throws SQLException {
    try (Connection first = pool.getConnection();
        Connection second = pool.getConnection()) {
      configureFollowerSession(first);
      configureFollowerSession(second);
      assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(2);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
    }
  }

  private static void configureFollowerSession(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("set lock_timeout='200ms'");
      statement.execute("set statement_timeout='2s'");
      try (var lockTimeout = statement.executeQuery("show lock_timeout")) {
        assertThat(lockTimeout.next()).isTrue();
        assertThat(lockTimeout.getString(1)).isEqualTo("200ms");
      }
      try (var statementTimeout = statement.executeQuery("show statement_timeout")) {
        assertThat(statementTimeout.next()).isTrue();
        assertThat(statementTimeout.getString(1)).isEqualTo("2s");
      }
    }
  }

  private static FollowerObservation observeFollower(
      String label,
      int index,
      CountDownLatch ready,
      CountDownLatch start,
      AtomicInteger completed,
      JdbcTagoArrivalFlightStore followerStore,
      String fingerprint) {
    awaitCoordinatedStart(ready, start);
    TagoArrivalFlightDecision decision =
        followerStore.observeOrClaim(fingerprint, new UUID(39L, index + 100L), LEASE, QUARANTINE);
    completed.incrementAndGet();
    return new FollowerObservation(label, decision, System.nanoTime());
  }

  private static void awaitCoordinatedStart(CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
  }

  private static FollowerObservation getBeforeDeadline(
      String label, Future<FollowerObservation> observer, long deadlineNanos) {
    return getBeforeDeadline(label, "DB observe", observer, deadlineNanos);
  }

  private static <T> T getBeforeDeadline(
      String actor, String stage, Future<T> observer, long deadlineNanos) {
    String context = actor + " " + stage;
    long remainingNanos = deadlineNanos - System.nanoTime();
    assertThat(remainingNanos).describedAs(context + " absolute deadline").isPositive();
    try {
      return observer.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException timeout) {
      throw new AssertionError(context + "가 absolute deadline 안에 완료되지 않았습니다.", timeout);
    } catch (ExecutionException failure) {
      throw new AssertionError(context + " 실패", failure.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(context + "가 absolute deadline 전에 interrupt됐습니다.", interrupted);
    }
  }

  private static void awaitBeforeDeadline(
      String actor, String stage, CountDownLatch latch, long deadlineNanos) {
    String context = actor + " " + stage;
    long remainingNanos = deadlineNanos - System.nanoTime();
    assertThat(remainingNanos).describedAs(context + " absolute deadline").isPositive();
    try {
      assertThat(latch.await(remainingNanos, TimeUnit.NANOSECONDS))
          .describedAs(context + " absolute deadline")
          .isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(context + "가 absolute deadline 전에 interrupt됐습니다.", interrupted);
    }
  }

  private static boolean containsAnySensitiveText(String output, String... sensitiveValues) {
    for (String sensitiveValue : sensitiveValues) {
      if (sensitiveValue != null && !sensitiveValue.isBlank() && output.contains(sensitiveValue)) {
        return true;
      }
    }
    return false;
  }

  private record FollowerObservation(
      String label, TagoArrivalFlightDecision decision, long completedAtNanos) {}

  private record FollowerTask(String label, Future<FollowerObservation> future) {}

  private record LabeledSnapshotTask(String actor, Future<TagoArrivalSnapshot> future) {}

  private record ExpiredReclaimFixture(TagoArrivalCacheKey key, String fingerprint) {}

  private static final class RunningStageReportingStore extends JdbcTagoArrivalFlightStore {
    private final CountDownLatch runningObserved;
    private final AtomicBoolean reported = new AtomicBoolean();

    private RunningStageReportingStore(JdbcTemplate jdbc, CountDownLatch runningObserved) {
      super(jdbc);
      this.runningObserved = runningObserved;
    }

    @Override
    public TagoArrivalFlightDecision observeOrClaim(
        String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
      TagoArrivalFlightDecision decision =
          super.observeOrClaim(fingerprint, proposedOwner, lease, quarantine);
      if (decision.status() == TagoArrivalFlightStatus.RUNNING
          && reported.compareAndSet(false, true)) {
        runningObserved.countDown();
      }
      return decision;
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
