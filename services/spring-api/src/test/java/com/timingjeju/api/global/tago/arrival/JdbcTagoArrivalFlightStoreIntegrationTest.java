package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
import org.springframework.transaction.support.TransactionTemplate;

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
    Instant sourceExpiresAt = Instant.now().plusSeconds(2);

    assertThat(store.completeSuccess(leader.lease(), sourceExpiresAt, RETAIN)).isTrue();
    Instant retainedUntil =
        jdbc.queryForObject(
                "select retain_until from public.tago_arrival_flights where fingerprint=?",
                java.sql.Timestamp.class,
                FINGERPRINT)
            .toInstant();
    assertThat(retainedUntil).isBeforeOrEqualTo(sourceExpiresAt);

    jdbc.update("delete from public.tago_arrival_flights");
    TagoArrivalFlightDecision expired =
        store.observeOrClaim(FINGERPRINT, new UUID(39L, 2L), LEASE, QUARANTINE);
    assertThat(store.completeSuccess(expired.lease(), Instant.now().minusSeconds(1), RETAIN))
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
  void processor_row_lock중_same_fingerprint_followers는_pool을_막지않고_deadline내_action0이다()
      throws Exception {
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

    try (HikariDataSource pool = new HikariDataSource(config);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lockHolder =
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
      try {
        var observers =
            IntStream.range(0, 20)
                .mapToObj(
                    index ->
                        executor.submit(
                            () ->
                                followerStore.observeOrClaim(
                                    lockedFingerprint,
                                    new UUID(39L, index + 100L),
                                    LEASE,
                                    QUARANTINE)))
                .toList();
        for (var observer : observers) {
          assertThat(observer.get(1, TimeUnit.SECONDS).status())
              .isEqualTo(TagoArrivalFlightStatus.RUNNING);
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

        AtomicInteger actions = new AtomicInteger();
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
        release.countDown();
        lockHolder.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void expired_FAILED_ABANDONED_reclaim_lock중_old_outcome없이_retry해_generation2_provider1이다()
      throws Exception {
    for (TagoArrivalFlightStatus terminal :
        List.of(TagoArrivalFlightStatus.FAILED, TagoArrivalFlightStatus.ABANDONED)) {
      clean();
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
                store.completeFailure(
                    first.lease(), TagoArrivalException.Code.EMPTY_RESULT, RETAIN))
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

      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicInteger providers = new AtomicInteger();
      TagoArrivalSnapshot fresh = snapshot(terminal.ordinal() + 600, databaseNow());
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var lockHolder =
            executor.submit(
                () ->
                    transactions.executeWithoutResult(
                        ignored -> {
                          jdbc.queryForObject(
                              "select generation from public.tago_arrival_flights where fingerprint=? for update",
                              Long.class,
                              lockedFingerprint);
                          locked.countDown();
                          await(release);
                        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(
                store
                    .observeOrClaim(lockedFingerprint, new UUID(39L, 2L), LEASE, QUARANTINE)
                    .status())
            .isEqualTo(TagoArrivalFlightStatus.CONTENDED);

        var requests =
            IntStream.range(0, 20)
                .mapToObj(
                    index ->
                        executor.submit(
                            () ->
                                new TagoArrivalDistributedFlightCoordinator(
                                        store,
                                        new TagoArrivalFlightPolicy(
                                            Duration.ofSeconds(2),
                                            Duration.ofMillis(10),
                                            Duration.ofSeconds(1),
                                            LEASE,
                                            RETAIN,
                                            QUARANTINE))
                                    .coalesce(
                                        key,
                                        ignored -> {
                                          providers.incrementAndGet();
                                          return fresh;
                                        },
                                        () -> fresh)))
                .toList();
        try {
          Thread.sleep(100);
        } finally {
          release.countDown();
          lockHolder.get(5, TimeUnit.SECONDS);
        }
        for (var request : requests) assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(fresh);
      }
      assertThat(providers).hasValue(1);
      assertThat(
              jdbc.queryForObject(
                  "select generation from public.tago_arrival_flights where fingerprint=?",
                  Long.class,
                  lockedFingerprint))
          .isEqualTo(2L);
    }
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

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
