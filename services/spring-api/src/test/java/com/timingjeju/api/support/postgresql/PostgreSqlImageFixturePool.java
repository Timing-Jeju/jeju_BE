package com.timingjeju.api.support.postgresql;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class PostgreSqlImageFixturePool implements AutoCloseable {
  private static final String SESSION_ID = UUID.randomUUID().toString();

  private final String sessionId;
  private final Function<String, PostgreSQLContainer> containerFactory;
  private final ImageResourcePool<SharedContainer> containers;

  PostgreSqlImageFixturePool(String exclusiveMigration) {
    this(
        SESSION_ID,
        image -> PostgreSqlTestContainerFactory.createBefore(exclusiveMigration, image));
  }

  PostgreSqlImageFixturePool(
      String sessionId, Function<String, PostgreSQLContainer> containerFactory) {
    this.sessionId = sessionId;
    this.containerFactory = containerFactory;
    this.containers = new ImageResourcePool<>(this::startContainer);
  }

  IsolatedDatabase open(String image) {
    return containers.get(image).createDatabase();
  }

  int startedContainerCount() {
    return containers.size();
  }

  @Override
  public void close() {
    containers.close();
  }

  private SharedContainer startContainer(String image) {
    long startedAt = System.nanoTime();
    PostgreSQLContainer container =
        containerFactory
            .apply(image)
            .withLabel("timing-jeju.test-session", sessionId)
            .withLabel("timing-jeju.fixture", "image-bounded-location-purge");
    try {
      container.start();
      return new SharedContainer(container);
    } catch (RuntimeException failure) {
      String containerId = container.getContainerId();
      String logs = safeLogs(container);
      var diagnosticFailure =
          new IllegalStateException(
              startupDiagnostic(
                  image,
                  containerId,
                  sessionId,
                  Duration.ofNanos(System.nanoTime() - startedAt),
                  logs),
              failure);
      try {
        container.stop();
      } catch (RuntimeException cleanupFailure) {
        diagnosticFailure.addSuppressed(cleanupFailure);
      }
      throw diagnosticFailure;
    }
  }

  static String startupDiagnostic(
      String image, String containerId, String sessionId, Duration elapsed, String logs) {
    return "PostGIS fixture readiness 실패"
        + " image="
        + image
        + " container="
        + (containerId == null ? "not-created" : containerId)
        + " session="
        + sessionId
        + " elapsed="
        + elapsed
        + " logs="
        + logs;
  }

  private static String safeLogs(PostgreSQLContainer container) {
    try {
      String logs = container.getLogs();
      return logs.length() <= 4000 ? logs : logs.substring(logs.length() - 4000);
    } catch (RuntimeException ignored) {
      return "unavailable";
    }
  }

  final class IsolatedDatabase implements AutoCloseable {
    private final SharedContainer owner;
    private final String databaseName;
    private final AtomicBoolean closed = new AtomicBoolean();

    private IsolatedDatabase(SharedContainer owner, String databaseName) {
      this.owner = owner;
      this.databaseName = databaseName;
    }

    String getJdbcUrl() {
      return owner.jdbcUrl(databaseName);
    }

    String getUsername() {
      return owner.container.getUsername();
    }

    String getPassword() {
      return owner.container.getPassword();
    }

    String getDatabaseName() {
      return databaseName;
    }

    JdbcTemplate jdbc() {
      return new JdbcTemplate(
          new DriverManagerDataSource(getJdbcUrl(), getUsername(), getPassword()));
    }

    void executeScript(Path script) throws Exception {
      PostgreSqlTestContainerFactory.executeScript(owner.container, databaseName, script);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) owner.dropDatabase(databaseName);
    }
  }

  private final class SharedContainer implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final JdbcTemplate admin;

    private SharedContainer(PostgreSQLContainer container) {
      this.container = container;
      this.admin =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  jdbcUrl("postgres"), container.getUsername(), container.getPassword()));
    }

    private synchronized IsolatedDatabase createDatabase() {
      String databaseName = "tj_case_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("create database " + databaseName + " template " + container.getDatabaseName());
      return new IsolatedDatabase(this, databaseName);
    }

    private synchronized void dropDatabase(String databaseName) {
      try {
        admin.queryForList(
            "select pg_terminate_backend(pid) from pg_stat_activity "
                + "where datname=? and pid<>pg_backend_pid()",
            Boolean.class,
            databaseName);
      } finally {
        admin.execute("drop database if exists " + databaseName + " with (force)");
      }
    }

    private String jdbcUrl(String databaseName) {
      return "jdbc:postgresql://"
          + container.getHost()
          + ":"
          + container.getMappedPort(5432)
          + "/"
          + databaseName;
    }

    @Override
    public void close() {
      container.stop();
    }
  }
}
