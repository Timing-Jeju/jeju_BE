package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightCoordinator;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class JdbcTagoArrivalFlightCoordinator implements TagoArrivalFlightCoordinator {
  private static final String TRY_LOCK_SQL = "select pg_try_advisory_lock(hashtextextended(?, 0))";
  private static final String UNLOCK_SQL = "select pg_advisory_unlock(hashtextextended(?, 0))";
  private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(10);
  private static final Duration DEFAULT_BACKOFF = Duration.ofMillis(25);

  private final DataSource dataSource;
  private final LongSupplier nanoTime;
  private final LongConsumer pause;
  private final long deadlineNanos;
  private final long backoffNanos;

  @Autowired
  public JdbcTagoArrivalFlightCoordinator(DataSource dataSource) {
    this(
        dataSource,
        System::nanoTime,
        JdbcTagoArrivalFlightCoordinator::sleep,
        DEFAULT_DEADLINE,
        DEFAULT_BACKOFF);
  }

  JdbcTagoArrivalFlightCoordinator(
      DataSource dataSource,
      LongSupplier nanoTime,
      LongConsumer pause,
      Duration deadline,
      Duration backoff) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource는 필수입니다.");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime은 필수입니다.");
    this.pause = Objects.requireNonNull(pause, "pause는 필수입니다.");
    this.deadlineNanos = requirePositive(deadline, "deadline");
    this.backoffNanos = requirePositive(backoff, "backoff");
    if (backoffNanos > deadlineNanos) {
      throw new IllegalArgumentException("backoff는 deadline보다 길 수 없습니다.");
    }
  }

  @Override
  public TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key, Supplier<TagoArrivalSnapshot> coordinatedAction) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(coordinatedAction, "coordinatedAction은 필수입니다.");
    String fingerprint = fingerprint(key);
    long startedAt = nanoTime.getAsLong();

    while (true) {
      requireNotInterrupted();
      Connection lockConnection = tryAcquire(fingerprint);
      if (lockConnection != null) {
        return executeAndUnlock(lockConnection, fingerprint, coordinatedAction);
      }

      long elapsed = nanoTime.getAsLong() - startedAt;
      if (elapsed < 0 || elapsed >= deadlineNanos) {
        throw TagoArrivalException.dataUnavailable();
      }
      long remaining = deadlineNanos - elapsed;
      pause.accept(Math.min(backoffNanos, remaining));
      requireNotInterrupted();
    }
  }

  static String fingerprint(TagoArrivalCacheKey key) {
    Objects.requireNonNull(key, "key는 필수입니다.");
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
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  private Connection tryAcquire(String fingerprint) {
    Connection connection = null;
    boolean acquired = false;
    try {
      connection = dataSource.getConnection();
      acquired = queryBoolean(connection, TRY_LOCK_SQL, fingerprint);
      return acquired ? connection : null;
    } catch (SQLException failure) {
      throw TagoArrivalException.dataUnavailable();
    } finally {
      if (!acquired && connection != null) close(connection);
    }
  }

  private TagoArrivalSnapshot executeAndUnlock(
      Connection connection, String fingerprint, Supplier<TagoArrivalSnapshot> coordinatedAction) {
    try (connection) {
      try {
        return coordinatedAction.get();
      } finally {
        if (!queryBoolean(connection, UNLOCK_SQL, fingerprint)) {
          throw TagoArrivalException.dataUnavailable();
        }
      }
    } catch (SQLException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static boolean queryBoolean(Connection connection, String sql, String fingerprint)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, fingerprint);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw TagoArrivalException.dataUnavailable();
        return result.getBoolean(1);
      }
    }
  }

  private static void close(Connection connection) {
    try {
      connection.close();
    } catch (SQLException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static String component(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return bytes.length + ":" + value;
  }

  private static long requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name + "은 필수입니다.");
    long nanos = value.toNanos();
    if (nanos <= 0) throw new IllegalArgumentException(name + "은 양수여야 합니다.");
    return nanos;
  }

  private static void sleep(long nanos) {
    try {
      TimeUnit.NANOSECONDS.sleep(nanos);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      Thread.currentThread().interrupt();
      throw TagoArrivalException.dataUnavailable();
    }
  }
}
