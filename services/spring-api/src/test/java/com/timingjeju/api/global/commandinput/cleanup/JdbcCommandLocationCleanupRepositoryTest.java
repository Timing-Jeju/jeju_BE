package com.timingjeju.api.global.commandinput.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCommand;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcCommandLocationCleanupRepositoryTest {

  @RepeatedTest(3)
  void deadline이_connection보다_먼저면_late_connection은_publish없이_exactly_once_close된다()
      throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    CountDownLatch acquisitionEntered = new CountDownLatch(1);
    CountDownLatch releaseAcquisition = new CountDownLatch(1);
    AtomicInteger published = new AtomicInteger();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              acquisitionEntered.countDown();
              awaitIgnoringInterrupt(releaseAcquisition);
              return connection;
            });
    try (TrackingExecutor acquisitionExecutor = new TrackingExecutor();
        var caller = Executors.newSingleThreadExecutor()) {
      var observer =
          new JdbcCommandLocationCleanupRepository.AcquisitionObserver() {
            @Override
            public void afterPublish() {
              published.incrementAndGet();
            }
          };
      var repository =
          new JdbcCommandLocationCleanupRepository(
              dataSource, acquisitionExecutor, System::nanoTime, observer);

      Future<Throwable> outcome =
          caller.submit(() -> executeAndCapture(repository, Duration.ofSeconds(1)));
      assertThat(acquisitionEntered.await(1, TimeUnit.SECONDS)).isTrue();
      assertClassified(outcome.get(2, TimeUnit.SECONDS));
      verifyNoDatabaseWork(connection);

      releaseAcquisition.countDown();
      assertThat(acquisitionExecutor.awaitTask()).isTrue();
      verify(connection).close();
      verifyNoDatabaseWork(connection);
      assertThat(published).hasValue(0);
    }
  }

  @RepeatedTest(3)
  void first_check뒤_publish직전_timeout이면_publish후_CAS가_late_connection을_닫는다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    CountDownLatch beforePublish = new CountDownLatch(1);
    CountDownLatch releasePublish = new CountDownLatch(1);
    try (TrackingExecutor acquisitionExecutor = new TrackingExecutor();
        var caller = Executors.newSingleThreadExecutor()) {
      var observer =
          new JdbcCommandLocationCleanupRepository.AcquisitionObserver() {
            @Override
            public void beforePublish() {
              beforePublish.countDown();
              awaitIgnoringInterrupt(releasePublish);
            }
          };
      var repository =
          new JdbcCommandLocationCleanupRepository(
              dataSource, acquisitionExecutor, System::nanoTime, observer);

      Future<Throwable> outcome =
          caller.submit(() -> executeAndCapture(repository, Duration.ofSeconds(1)));
      assertThat(beforePublish.await(1, TimeUnit.SECONDS)).isTrue();
      assertClassified(outcome.get(2, TimeUnit.SECONDS));
      releasePublish.countDown();

      assertThat(acquisitionExecutor.awaitTask()).isTrue();
      verify(connection).close();
      verifyNoDatabaseWork(connection);
    }
  }

  @RepeatedTest(3)
  void publish직후_timeout이면_caller가_즉시_close하고_task_CAS는_double_close하지_않는다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    CountDownLatch afterPublish = new CountDownLatch(1);
    CountDownLatch releaseTask = new CountDownLatch(1);
    try (TrackingExecutor acquisitionExecutor = new TrackingExecutor();
        var caller = Executors.newSingleThreadExecutor()) {
      var observer =
          new JdbcCommandLocationCleanupRepository.AcquisitionObserver() {
            @Override
            public void afterPublish() {
              afterPublish.countDown();
              awaitIgnoringInterrupt(releaseTask);
            }
          };
      var repository =
          new JdbcCommandLocationCleanupRepository(
              dataSource, acquisitionExecutor, System::nanoTime, observer);

      Future<Throwable> outcome =
          caller.submit(() -> executeAndCapture(repository, Duration.ofSeconds(1)));
      assertThat(afterPublish.await(1, TimeUnit.SECONDS)).isTrue();
      assertClassified(outcome.get(2, TimeUnit.SECONDS));
      verify(connection).close();
      verifyNoDatabaseWork(connection);

      releaseTask.countDown();
      assertThat(acquisitionExecutor.awaitTask()).isTrue();
      verify(connection).close();
    }
  }

  @RepeatedTest(3)
  void acquisition_timeout은_blocked_task를_cancel하고_executor_task를_종료한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch acquisitionEntered = new CountDownLatch(1);
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              acquisitionEntered.countDown();
              new CountDownLatch(1).await();
              throw new AssertionError("interruptible acquisition이 release 없이 반환했습니다.");
            });
    try (TrackingExecutor acquisitionExecutor = new TrackingExecutor();
        var caller = Executors.newSingleThreadExecutor()) {
      var repository =
          new JdbcCommandLocationCleanupRepository(
              dataSource,
              acquisitionExecutor,
              System::nanoTime,
              JdbcCommandLocationCleanupRepository.AcquisitionObserver.NOOP);

      Future<Throwable> outcome =
          caller.submit(() -> executeAndCapture(repository, Duration.ofSeconds(1)));
      assertThat(acquisitionEntered.await(1, TimeUnit.SECONDS)).isTrue();
      assertClassified(outcome.get(2, TimeUnit.SECONDS));
      assertThat(acquisitionExecutor.awaitTask()).isTrue();
    }
  }

  @Test
  void owned_executor_close는_idempotent하고_이후_execute도_raw_rejection없이_분류된다() {
    DataSource dataSource = mock(DataSource.class);
    var repository = new JdbcCommandLocationCleanupRepository(dataSource);

    repository.close();
    repository.close();

    assertClassified(executeAndCapture(repository, Duration.ofSeconds(1)));
    verifyNoInteractions(dataSource);
  }

  @Test
  void 고갈된_Hikari_connection획득도_1초_attempt_deadline안에_중단한다() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:cleanup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(3_000L);

    try (HikariDataSource dataSource = new HikariDataSource(config);
        var heldConnection = dataSource.getConnection();
        var repository = new JdbcCommandLocationCleanupRepository(dataSource)) {
      long started = System.nanoTime();

      assertThatThrownBy(
              () ->
                  repository.execute(
                      command(Instant.parse("2026-08-24T12:00:00Z"), Duration.ofSeconds(1))))
          .isExactlyInstanceOf(CommandLocationCleanupException.class);

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
      assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
    }
  }

  @Test
  void connection획득후_deadline이면_SET_LOCAL과_cleanup_SQL을_시작하지_않는다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    AtomicLong now = new AtomicLong();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              now.set(Duration.ofSeconds(1).toNanos());
              return connection;
            });
    var executor = Executors.newSingleThreadExecutor();
    var repository = new JdbcCommandLocationCleanupRepository(dataSource, executor, now::get);

    try {
      assertThatThrownBy(
              () ->
                  repository.execute(
                      command(Instant.parse("2026-08-24T12:00:00Z"), Duration.ofSeconds(1))))
          .isExactlyInstanceOf(CommandLocationCleanupException.class);

      verify(connection, never()).prepareStatement(anyString());
      verify(connection).close();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void repository는_제한된_DB_transition만_호출하고_위치를_projection하지_않는다() {
    assertThat(JdbcCommandLocationCleanupRepository.REDACT_SQL)
        .isEqualTo("select public.redact_due_compute_run_input_locations(?, ?)")
        .doesNotContain("coarse_location", "location_precision", "structured_input", "select *");
  }

  @Test
  void connection획득에_999ms를_쓴뒤_남은_1ms를_network와_SET_LOCAL에_적용한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement timeoutStatement = mock(PreparedStatement.class);
    PreparedStatement redactStatement = mock(PreparedStatement.class);
    ResultSet timeoutResult = mock(ResultSet.class);
    ResultSet redactResult = mock(ResultSet.class);
    AtomicLong now = new AtomicLong();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              now.set(Duration.ofMillis(999).toNanos());
              return connection;
            });
    when(connection.prepareStatement(JdbcCommandLocationCleanupRepository.SET_TIMEOUT_SQL))
        .thenReturn(timeoutStatement);
    when(connection.prepareStatement(JdbcCommandLocationCleanupRepository.REDACT_SQL))
        .thenReturn(redactStatement);
    when(timeoutStatement.executeQuery()).thenReturn(timeoutResult);
    when(timeoutResult.next()).thenReturn(true);
    when(redactStatement.executeQuery()).thenReturn(redactResult);
    when(redactResult.next()).thenReturn(true);
    when(redactResult.getInt(1)).thenReturn(1);
    var executor = Executors.newSingleThreadExecutor();
    var repository = new JdbcCommandLocationCleanupRepository(dataSource, executor, now::get);
    Instant evaluatedAt = Instant.parse("2026-08-24T12:00:00.123456Z");

    try {
      var result = repository.execute(command(evaluatedAt, Duration.ofSeconds(1)));

      assertThat(result.redactedCount()).isOne();
      verify(connection).setAutoCommit(false);
      verify(connection, atLeastOnce()).setNetworkTimeout(any(), eq(1));
      verify(timeoutStatement).setString(1, "1ms");
      verify(redactStatement).setTimestamp(1, Timestamp.from(evaluatedAt));
      verify(redactStatement).setInt(2, 500);
      verify(connection).commit();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void DB_failure는_rollback하고_raw_위치나_SQL_cause없는_분류_code로_변환한다() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement timeoutStatement = mock(PreparedStatement.class);
    PreparedStatement redactStatement = mock(PreparedStatement.class);
    ResultSet timeoutResult = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(JdbcCommandLocationCleanupRepository.SET_TIMEOUT_SQL))
        .thenReturn(timeoutStatement);
    when(connection.prepareStatement(JdbcCommandLocationCleanupRepository.REDACT_SQL))
        .thenReturn(redactStatement);
    when(timeoutStatement.executeQuery()).thenReturn(timeoutResult);
    when(timeoutResult.next()).thenReturn(true);
    when(redactStatement.executeQuery())
        .thenThrow(new SQLException("coarse_location=secret SELECT raw"));
    var executor = Executors.newSingleThreadExecutor();
    var repository = new JdbcCommandLocationCleanupRepository(dataSource, executor, () -> 0L);

    try {
      assertThatThrownBy(
              () ->
                  repository.execute(
                      command(Instant.parse("2026-08-24T12:00:00Z"), Duration.ofSeconds(1))))
          .isExactlyInstanceOf(CommandLocationCleanupException.class)
          .hasMessage("COMMAND_LOCATION_CLEANUP_UNAVAILABLE")
          .hasNoCause();
      verify(connection).rollback();
    } finally {
      executor.shutdownNow();
    }
  }

  private static CommandLocationCleanupCommand command(Instant evaluatedAt, Duration timeout) {
    return new CommandLocationCleanupCommand(evaluatedAt, 500, timeout);
  }

  private static Throwable executeAndCapture(
      JdbcCommandLocationCleanupRepository repository, Duration timeout) {
    try {
      repository.execute(command(Instant.parse("2026-08-24T12:00:00Z"), timeout));
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static void assertClassified(Throwable failure) {
    assertThat(failure)
        .isExactlyInstanceOf(CommandLocationCleanupException.class)
        .hasMessage("COMMAND_LOCATION_CLEANUP_UNAVAILABLE")
        .hasNoCause();
  }

  private static void verifyNoDatabaseWork(Connection connection) throws Exception {
    verify(connection, never()).setAutoCommit(false);
    verify(connection, never()).prepareStatement(anyString());
    verify(connection, never()).commit();
    verify(connection, never()).rollback();
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static final class TrackingExecutor extends ThreadPoolExecutor implements AutoCloseable {
    private final CountDownLatch taskCompleted = new CountDownLatch(1);

    private TrackingExecutor() {
      super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    protected void afterExecute(Runnable task, Throwable failure) {
      taskCompleted.countDown();
      super.afterExecute(task, failure);
    }

    boolean awaitTask() throws InterruptedException {
      return taskCompleted.await(1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      shutdownNow();
    }
  }
}
