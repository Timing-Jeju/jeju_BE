package com.timingjeju.api.global.tago.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("unit")
class JdbcTagoArrivalFlightCoordinatorTest {
  private static final TagoArrivalCacheKey KEY =
      TagoArrivalCacheKey.tago(
          UUID.fromString("39000000-0000-0000-0000-000000000001"), "39", "JEP123");

  @Test
  void Spring은_DataSource_constructor를_선택해_component를_생성한다() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(DataSource.class, () -> mock(DataSource.class));
      context.register(JdbcTagoArrivalFlightCoordinator.class);
      context.refresh();

      assertThat(context.getBean(JdbcTagoArrivalFlightCoordinator.class)).isNotNull();
    }
  }

  @Test
  void canonical_fingerprint는_provider_service_city_stop_node전체를_안정적으로_포함한다() {
    String fingerprint = JdbcTagoArrivalFlightCoordinator.fingerprint(KEY);

    assertThat(fingerprint).matches("[0-9a-f]{64}");
    assertThat(JdbcTagoArrivalFlightCoordinator.fingerprint(KEY)).isEqualTo(fingerprint);
    assertThat(
            JdbcTagoArrivalFlightCoordinator.fingerprint(
                TagoArrivalCacheKey.tago(KEY.stopId(), "40", "JEP123")))
        .isNotEqualTo(fingerprint);
    assertThat(
            JdbcTagoArrivalFlightCoordinator.fingerprint(
                TagoArrivalCacheKey.tago(KEY.stopId(), "39", "JEP124")))
        .isNotEqualTo(fingerprint);
    assertThat(
            JdbcTagoArrivalFlightCoordinator.fingerprint(
                new TagoArrivalCacheKey(
                    "OTHER", KEY.service(), KEY.stopId(), KEY.cityCode(), KEY.nodeId())))
        .isNotEqualTo(fingerprint);
    assertThat(
            JdbcTagoArrivalFlightCoordinator.fingerprint(
                new TagoArrivalCacheKey(
                    KEY.provider(), "OtherService", KEY.stopId(), KEY.cityCode(), KEY.nodeId())))
        .isNotEqualTo(fingerprint);
  }

  @Test
  void loser는_connection을_반환한뒤_backoff하고_winner는_같은_connection으로_unlock한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    AtomicBoolean loserClosed = new AtomicBoolean();
    AtomicBoolean winnerClosed = new AtomicBoolean();
    Connection loser = connection(false, true, loserClosed);
    Connection winner = connection(true, true, winnerClosed);
    when(dataSource.getConnection()).thenReturn(loser, winner);
    AtomicLong nanos = new AtomicLong();
    JdbcTagoArrivalFlightCoordinator coordinator =
        new JdbcTagoArrivalFlightCoordinator(
            dataSource,
            nanos::get,
            delay -> {
              assertThat(loserClosed).isTrue();
              nanos.addAndGet(delay);
            },
            Duration.ofMillis(10),
            Duration.ofMillis(1));

    TagoArrivalSnapshot result =
        coordinator.coalesce(
            KEY,
            () -> {
              assertThat(winnerClosed).isFalse();
              return snapshot();
            });

    assertThat(result).isEqualTo(snapshot());
    assertThat(winnerClosed).isTrue();
  }

  @Test
  void deadline이면_provider_action을_호출하지_않고_DATA_UNAVAILABLE다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection())
        .thenAnswer(ignored -> connection(false, true, new AtomicBoolean()));
    AtomicLong nanos = new AtomicLong();
    AtomicInteger actions = new AtomicInteger();
    JdbcTagoArrivalFlightCoordinator coordinator =
        new JdbcTagoArrivalFlightCoordinator(
            dataSource, nanos::get, nanos::addAndGet, Duration.ofMillis(2), Duration.ofMillis(1));

    assertThatThrownBy(
            () ->
                coordinator.coalesce(
                    KEY,
                    () -> {
                      actions.incrementAndGet();
                      return snapshot();
                    }))
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure ->
                assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE));
    assertThat(actions).hasValue(0);
  }

  @Test
  void unlock_false와_SQL_failure는_raw없이_DATA_UNAVAILABLE다() throws Exception {
    DataSource unlockDataSource = mock(DataSource.class);
    Connection unlockConnection = connection(true, false, new AtomicBoolean());
    when(unlockDataSource.getConnection()).thenReturn(unlockConnection);
    JdbcTagoArrivalFlightCoordinator unlockFailure = coordinator(unlockDataSource);

    assertDataUnavailable(
        () -> unlockFailure.coalesce(KEY, JdbcTagoArrivalFlightCoordinatorTest::snapshot));

    DataSource sqlDataSource = mock(DataSource.class);
    when(sqlDataSource.getConnection())
        .thenThrow(new java.sql.SQLException("select credential from lock_table"));
    assertDataUnavailable(
        () ->
            coordinator(sqlDataSource)
                .coalesce(KEY, JdbcTagoArrivalFlightCoordinatorTest::snapshot));
  }

  @Test
  void interrupt는_flag를_복원하고_provider_action을_호출하지_않는다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = connection(false, true, new AtomicBoolean());
    when(dataSource.getConnection()).thenReturn(connection);
    AtomicInteger actions = new AtomicInteger();
    Thread.currentThread().interrupt();
    try {
      assertDataUnavailable(
          () ->
              coordinator(dataSource)
                  .coalesce(
                      KEY,
                      () -> {
                        actions.incrementAndGet();
                        return snapshot();
                      }));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(actions).hasValue(0);
    } finally {
      Thread.interrupted();
    }
  }

  private static JdbcTagoArrivalFlightCoordinator coordinator(DataSource dataSource) {
    return new JdbcTagoArrivalFlightCoordinator(
        dataSource, System::nanoTime, ignored -> {}, Duration.ofMillis(2), Duration.ofMillis(1));
  }

  private static Connection connection(boolean acquired, boolean unlocked, AtomicBoolean closed)
      throws Exception {
    Connection connection = mock(Connection.class);
    PreparedStatement lock = mock(PreparedStatement.class);
    PreparedStatement unlock = mock(PreparedStatement.class);
    ResultSet lockResult = mock(ResultSet.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lock);
    when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlock);
    when(lock.executeQuery()).thenReturn(lockResult);
    when(unlock.executeQuery()).thenReturn(unlockResult);
    when(lockResult.next()).thenReturn(true);
    when(unlockResult.next()).thenReturn(true);
    when(lockResult.getBoolean(1)).thenReturn(acquired);
    when(unlockResult.getBoolean(1)).thenReturn(unlocked);
    doAnswer(
            ignored -> {
              closed.set(true);
              return null;
            })
        .when(connection)
        .close();
    return connection;
  }

  private static void assertDataUnavailable(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            TagoArrivalException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(TagoArrivalException.Code.DATA_UNAVAILABLE);
              assertThat(failure.getMessage()).isEqualTo("DATA_UNAVAILABLE");
              assertThat(failure.getCause()).isNull();
            });
  }

  private static TagoArrivalSnapshot snapshot() {
    return new TagoArrivalSnapshot(
        List.of(new TagoArrival("JER001", "201", "간선", "일반", 60, 1)),
        Instant.parse("2026-08-21T00:00:00Z"),
        Instant.parse("2026-08-21T00:00:25Z"),
        false,
        UUID.fromString("39000000-0000-0000-0000-000000000002"),
        UUID.fromString("39000000-0000-0000-0000-000000000003"));
  }
}
