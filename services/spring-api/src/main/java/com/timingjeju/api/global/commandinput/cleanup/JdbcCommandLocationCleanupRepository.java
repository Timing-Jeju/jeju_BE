package com.timingjeju.api.global.commandinput.cleanup;

import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCommand;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupException;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupOutcome;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupPort;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupResult;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommandLocationCleanupRepository
    implements CommandLocationCleanupPort, AutoCloseable {
  static final String REDACT_SQL = "select public.redact_due_compute_run_input_locations(?, ?)";
  static final String SET_TIMEOUT_SQL =
      "select pg_catalog.set_config('statement_timeout', ?, true)";
  private static final Duration MINIMUM_DB_BUDGET = Duration.ofMillis(1);

  private final DataSource dataSource;
  private final ExecutorService connectionExecutor;
  private final LongSupplier nanoTime;
  private final boolean ownsExecutor;
  private final AcquisitionObserver acquisitionObserver;

  @Autowired
  public JdbcCommandLocationCleanupRepository(DataSource dataSource) {
    this(
        dataSource,
        Executors.newVirtualThreadPerTaskExecutor(),
        System::nanoTime,
        true,
        AcquisitionObserver.NOOP);
  }

  JdbcCommandLocationCleanupRepository(
      DataSource dataSource, ExecutorService connectionExecutor, LongSupplier nanoTime) {
    this(dataSource, connectionExecutor, nanoTime, AcquisitionObserver.NOOP);
  }

  JdbcCommandLocationCleanupRepository(
      DataSource dataSource,
      ExecutorService connectionExecutor,
      LongSupplier nanoTime,
      AcquisitionObserver acquisitionObserver) {
    this(dataSource, connectionExecutor, nanoTime, false, acquisitionObserver);
  }

  private JdbcCommandLocationCleanupRepository(
      DataSource dataSource,
      ExecutorService connectionExecutor,
      LongSupplier nanoTime,
      boolean ownsExecutor,
      AcquisitionObserver acquisitionObserver) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource는 필수입니다.");
    this.connectionExecutor =
        Objects.requireNonNull(connectionExecutor, "connectionExecutor는 필수입니다.");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime은 필수입니다.");
    this.ownsExecutor = ownsExecutor;
    this.acquisitionObserver =
        Objects.requireNonNull(acquisitionObserver, "acquisitionObserver는 필수입니다.");
  }

  @Override
  public CommandLocationCleanupResult execute(CommandLocationCleanupCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    long started = nanoTime.getAsLong();
    long deadline = started + command.attemptTimeout().toNanos();
    try (Connection connection = acquireConnection(deadline, command.attemptTimeout())) {
      connection.setAutoCommit(false);
      try {
        configureStatementTimeout(connection, deadline, command.attemptTimeout());
        int count = redact(connection, command, deadline, command.attemptTimeout());
        if (count < 0 || count > command.batchSize()) {
          throw new SQLException("invalid cleanup count");
        }
        applyNetworkTimeout(connection, requireRemaining(deadline, command.attemptTimeout()));
        connection.commit();
        return new CommandLocationCleanupResult(
            count,
            Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - started)),
            CommandLocationCleanupOutcome.SUCCESS);
      } catch (SQLException failure) {
        rollbackQuietly(connection);
        throw failure;
      }
    } catch (SQLException failure) {
      throw CommandLocationCleanupException.unavailable();
    }
  }

  private Connection acquireConnection(long deadline, Duration maxDuration) throws SQLException {
    requireRemaining(deadline, maxDuration);
    AtomicBoolean timedOut = new AtomicBoolean();
    AtomicReference<Connection> acquired = new AtomicReference<>();
    Future<Connection> future;
    try {
      future =
          connectionExecutor.submit(
              () -> {
                Connection connection = dataSource.getConnection();
                if (timedOut.get()) {
                  closeQuietly(connection);
                  throw new SQLException("connection acquisition deadline exceeded");
                }
                acquisitionObserver.beforePublish();
                acquired.set(connection);
                acquisitionObserver.afterPublish();
                if (timedOut.get() && acquired.compareAndSet(connection, null)) {
                  closeQuietly(connection);
                  throw new SQLException("connection acquisition deadline exceeded");
                }
                return connection;
              });
    } catch (RejectedExecutionException failure) {
      throw new SQLException("connection acquisition executor is unavailable");
    }
    try {
      Duration waitBudget = requireRemaining(deadline, maxDuration);
      Connection connection = future.get(waitBudget.toNanos(), TimeUnit.NANOSECONDS);
      acquired.compareAndSet(connection, null);
      return connection;
    } catch (SQLException failure) {
      timedOut.set(true);
      closeQuietly(acquired.getAndSet(null));
      future.cancel(true);
      throw failure;
    } catch (TimeoutException failure) {
      timedOut.set(true);
      closeQuietly(acquired.getAndSet(null));
      future.cancel(true);
      throw new SQLException("connection acquisition deadline exceeded");
    } catch (InterruptedException failure) {
      timedOut.set(true);
      closeQuietly(acquired.getAndSet(null));
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new SQLException("connection acquisition interrupted");
    } catch (ExecutionException failure) {
      closeQuietly(acquired.getAndSet(null));
      throw new SQLException("connection acquisition failed");
    }
  }

  private void configureStatementTimeout(Connection connection, long deadline, Duration maxDuration)
      throws SQLException {
    Duration remaining = requireRemaining(deadline, maxDuration);
    long timeoutMillis = remaining.toMillis();
    applyNetworkTimeout(connection, remaining);
    try (PreparedStatement statement = connection.prepareStatement(SET_TIMEOUT_SQL)) {
      statement.setString(1, timeoutMillis + "ms");
      try (ResultSet configured = statement.executeQuery()) {
        if (!configured.next()) {
          throw new SQLException("statement timeout was not configured");
        }
      }
    }
  }

  private int redact(
      Connection connection,
      CommandLocationCleanupCommand command,
      long deadline,
      Duration maxDuration)
      throws SQLException {
    applyNetworkTimeout(connection, requireRemaining(deadline, maxDuration));
    try (PreparedStatement statement = connection.prepareStatement(REDACT_SQL)) {
      statement.setTimestamp(1, Timestamp.from(command.evaluatedAt()));
      statement.setInt(2, command.batchSize());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("cleanup count was not returned");
        }
        return result.getInt(1);
      }
    }
  }

  private void applyNetworkTimeout(Connection connection, Duration remaining) throws SQLException {
    connection.setNetworkTimeout(connectionExecutor, Math.toIntExact(remaining.toMillis()));
  }

  private Duration requireRemaining(long deadline, Duration maxDuration) throws SQLException {
    long remainingNanos = deadline - nanoTime.getAsLong();
    if (remainingNanos < MINIMUM_DB_BUDGET.toNanos()) {
      throw new SQLException("cleanup deadline exhausted");
    }
    return Duration.ofNanos(Math.min(remainingNanos, maxDuration.toNanos()));
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The classified cleanup error deliberately omits raw database causes.
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // A timed-out acquisition must not expose or replace the classified failure.
    }
  }

  @Override
  @PreDestroy
  public void close() {
    if (ownsExecutor) {
      connectionExecutor.shutdownNow();
    }
  }

  interface AcquisitionObserver {
    AcquisitionObserver NOOP = new AcquisitionObserver() {};

    default void beforePublish() {}

    default void afterPublish() {}
  }
}
