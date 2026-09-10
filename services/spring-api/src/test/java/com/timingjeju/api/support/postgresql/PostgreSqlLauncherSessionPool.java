package com.timingjeju.api.support.postgresql;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

final class PostgreSqlLauncherSessionPool {
  private static final String SESSION_ID = UUID.randomUUID().toString();
  private static final ImageResourcePool<SharedImage> IMAGES =
      new ImageResourcePool<>(PostgreSqlLauncherSessionPool::startImage);

  private PostgreSqlLauncherSessionPool() {}

  static PostgreSQLContainer container(DockerImageName image, List<Path> initScripts) {
    return new PooledContainer(image, List.copyOf(initScripts));
  }

  static int startedImageCount() {
    return IMAGES.size();
  }

  static int cachedTemplateCount(String canonicalImage) {
    return IMAGES.get(canonicalImage).templateCount();
  }

  static void closeSession() {
    IMAGES.close();
  }

  private static SharedImage startImage(String canonicalImage) {
    long startedAt = System.nanoTime();
    Path authCompatibility =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("db/local-postgres/auth_compat.sql");
    PostgreSQLContainer container =
        new PostgreSQLContainer(
                DockerImageName.parse(canonicalImage).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("timing_jeju_launcher_base")
            .withUsername("timing_jeju_repository_test")
            .withPassword(UUID.randomUUID().toString())
            .withTmpFs(PostgreSqlTestContainerFactory.dataDirectoryTmpFs())
            .withLabel("timing-jeju.test-session", SESSION_ID)
            .withLabel("timing-jeju.fixture", "launcher-image-pool")
            .withCopyFileToContainer(
                MountableFile.forHostPath(authCompatibility),
                "/docker-entrypoint-initdb.d/001_auth_compat.sql")
            .withStartupTimeout(Duration.ofMinutes(3));
    try {
      container.start();
      return new SharedImage(container);
    } catch (RuntimeException failure) {
      String diagnostic =
          PostgreSqlImageFixturePool.startupDiagnostic(
              canonicalImage,
              container.getContainerId(),
              SESSION_ID,
              Duration.ofNanos(System.nanoTime() - startedAt),
              safeLogs(container));
      var wrapped = new IllegalStateException(diagnostic, failure);
      try {
        container.stop();
      } catch (RuntimeException cleanupFailure) {
        wrapped.addSuppressed(cleanupFailure);
      }
      throw wrapped;
    }
  }

  private static String safeLogs(PostgreSQLContainer container) {
    try {
      String logs = container.getLogs();
      return logs.length() <= 4000 ? logs : logs.substring(logs.length() - 4000);
    } catch (RuntimeException ignored) {
      return "unavailable";
    }
  }

  private static final class SharedImage implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final org.springframework.jdbc.core.JdbcTemplate admin;
    private final Map<List<String>, String> templates = new LinkedHashMap<>();

    private SharedImage(PostgreSQLContainer container) {
      this.container = container;
      this.admin =
          new org.springframework.jdbc.core.JdbcTemplate(
              new org.springframework.jdbc.datasource.DriverManagerDataSource(
                  jdbcUrl("postgres"), container.getUsername(), container.getPassword()));
    }

    private synchronized DatabaseLease open(List<Path> initScripts) {
      List<Path> migrations =
          initScripts.stream()
              .filter(path -> !path.getFileName().toString().equals("auth_compat.sql"))
              .toList();
      List<String> templateKey =
          migrations.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList();
      String template =
          templates.computeIfAbsent(templateKey, ignored -> createTemplate(migrations));
      String database = databaseName("tj_case_");
      admin.execute("create database " + database + " template " + template);
      return new DatabaseLease(this, database);
    }

    private synchronized int templateCount() {
      return templates.size();
    }

    private String createTemplate(List<Path> migrations) {
      String template = databaseName("tj_template_");
      admin.execute("create database " + template + " template " + container.getDatabaseName());
      try {
        for (Path migration : migrations) {
          PostgreSqlTestContainerFactory.executeScript(container, template, migration);
        }
        return template;
      } catch (Exception failure) {
        dropDatabase(template);
        throw new IllegalStateException(
            "PostgreSQL migration template 생성 실패: " + template, failure);
      }
    }

    private synchronized void dropDatabase(String database) {
      try {
        admin.queryForList(
            "select pg_terminate_backend(pid) from pg_stat_activity "
                + "where datname=? and pid<>pg_backend_pid()",
            Boolean.class,
            database);
      } finally {
        admin.execute("drop database if exists " + database + " with (force)");
      }
    }

    private synchronized void releaseDatabase(String database) {
      if (container.isRunning()) dropDatabase(database);
    }

    private String jdbcUrl(String database) {
      return "jdbc:postgresql://"
          + container.getHost()
          + ":"
          + container.getMappedPort(5432)
          + "/"
          + database;
    }

    @Override
    public synchronized void close() {
      templates.clear();
      container.stop();
    }
  }

  private static final class DatabaseLease implements AutoCloseable {
    private final SharedImage owner;
    private final String database;
    private boolean closed;

    private DatabaseLease(SharedImage owner, String database) {
      this.owner = owner;
      this.database = database;
    }

    private synchronized void requireOpen() {
      if (closed) throw new IllegalStateException("이미 종료한 PostgreSQL test database입니다.");
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      owner.releaseDatabase(database);
    }
  }

  private static final class PooledContainer extends PostgreSQLContainer {
    private final String canonicalImage;
    private final List<Path> initScripts;
    private DatabaseLease lease;

    private PooledContainer(DockerImageName image, List<Path> initScripts) {
      super(image);
      this.canonicalImage = image.asCanonicalNameString();
      this.initScripts = initScripts;
    }

    @Override
    public synchronized void start() {
      if (lease != null) return;
      lease = IMAGES.get(canonicalImage).open(initScripts);
    }

    @Override
    public synchronized void stop() {
      if (lease == null) return;
      DatabaseLease current = lease;
      lease = null;
      current.close();
    }

    @Override
    public synchronized boolean isRunning() {
      return lease != null && lease.owner.container.isRunning();
    }

    @Override
    public synchronized String getJdbcUrl() {
      DatabaseLease current = requireLease();
      return current.owner.jdbcUrl(current.database);
    }

    @Override
    public synchronized String getDatabaseName() {
      return requireLease().database;
    }

    @Override
    public synchronized String getUsername() {
      return requireLease().owner.container.getUsername();
    }

    @Override
    public synchronized String getPassword() {
      return requireLease().owner.container.getPassword();
    }

    @Override
    public synchronized String getHost() {
      return requireLease().owner.container.getHost();
    }

    @Override
    public synchronized Integer getMappedPort(int originalPort) {
      return requireLease().owner.container.getMappedPort(originalPort);
    }

    @Override
    public synchronized String getContainerId() {
      return lease == null ? null : lease.owner.container.getContainerId();
    }

    @Override
    public synchronized String getLogs() {
      return lease == null ? "" : lease.owner.container.getLogs();
    }

    @Override
    public synchronized ExecResult execInContainer(String... command)
        throws UnsupportedOperationException, IOException, InterruptedException {
      return requireLease().owner.container.execInContainer(command);
    }

    @Override
    public synchronized ExecResult execInContainer(Charset charset, String... command)
        throws UnsupportedOperationException, IOException, InterruptedException {
      return requireLease().owner.container.execInContainer(charset, command);
    }

    @Override
    public synchronized void copyFileToContainer(
        MountableFile mountableFile, String containerPath) {
      requireLease().owner.container.copyFileToContainer(mountableFile, containerPath);
    }

    @Override
    public synchronized void copyFileToContainer(Transferable transferable, String containerPath) {
      requireLease().owner.container.copyFileToContainer(transferable, containerPath);
    }

    private DatabaseLease requireLease() {
      if (lease == null) throw new IllegalStateException("PostgreSQL test database가 시작되지 않았습니다.");
      lease.requireOpen();
      return lease;
    }
  }

  private static String databaseName(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }
}
