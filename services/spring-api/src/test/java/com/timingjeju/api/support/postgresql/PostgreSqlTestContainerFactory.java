package com.timingjeju.api.support.postgresql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

final class PostgreSqlTestContainerFactory {

  static final String DOCKER_UNAVAILABLE_MESSAGE =
      "PostgreSQL Repository 통합 테스트를 실행하려면 Docker daemon이 실행 중이어야 합니다.";

  private static final DockerImageName POSTGIS_IMAGE =
      DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");
  private static final Pattern CANONICAL_MIGRATION = Pattern.compile("^\\d{14}_.+\\.sql$");
  private static final Pattern SQLSTATE_ERROR =
      Pattern.compile("\\bERROR:\\s*([0-9A-Z]{5})\\b(.*)", Pattern.CASE_INSENSITIVE);
  private static final Pattern SINGLE_QUOTED_VALUE = Pattern.compile("'(?:''|[^'])*'");
  private static final Pattern DOUBLE_QUOTED_VALUE = Pattern.compile("\"(?:\"\"|[^\"])*\"");
  private static final Pattern UNTAGGED_DOLLAR_QUOTED_VALUE =
      Pattern.compile("\\$\\$.*?\\$\\$", Pattern.DOTALL);
  private static final Pattern TAGGED_DOLLAR_QUOTED_VALUE =
      Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\$.*?\\$\\1\\$", Pattern.DOTALL);
  private static final Pattern FILESYSTEM_PATH = Pattern.compile("(?i)(?:[a-z]:\\\\|/)[^\\s,;)]*");
  private static final Pattern UUID_VALUE =
      Pattern.compile("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b");
  private static final int MAX_DIAGNOSTIC_LENGTH = 512;

  private PostgreSqlTestContainerFactory() {}

  static PostgreSQLContainer create() {
    return createWithScripts(canonicalInitScripts(locateRepositoryRoot()));
  }

  static PostgreSQLContainer createBefore(String exclusiveMigration) {
    return createBefore(exclusiveMigration, POSTGIS_IMAGE.asCanonicalNameString());
  }

  static PostgreSQLContainer createBefore(String exclusiveMigration, String image) {
    List<Path> scripts = canonicalInitScripts(locateRepositoryRoot());
    int targetIndex = -1;
    for (int index = 0; index < scripts.size(); index++) {
      if (exclusiveMigration.equals(scripts.get(index).getFileName().toString())) {
        targetIndex = index;
        break;
      }
    }
    if (targetIndex < 0) {
      throw new IllegalStateException("대상 Supabase migration이 없습니다: " + exclusiveMigration);
    }
    return createWithScripts(
        scripts.subList(0, targetIndex),
        DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
  }

  static void executeScript(PostgreSQLContainer container, Path script) throws Exception {
    String target = "/tmp/" + UUID.randomUUID() + "_" + script.getFileName();
    container.copyFileToContainer(MountableFile.forHostPath(script), target);
    var result =
        container.execInContainer(
            "psql",
            "--no-psqlrc",
            "--set",
            "ON_ERROR_STOP=1",
            "--set",
            "VERBOSITY=sqlstate",
            "--username",
            container.getUsername(),
            "--dbname",
            container.getDatabaseName(),
            "--file",
            target);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          "PostgreSQL script 실행 실패: "
              + script.getFileName()
              + " (exit="
              + result.getExitCode()
              + ", error="
              + safePsqlErrorSummary(result.getStderr())
              + ")");
    }
  }

  static String safePsqlErrorSummary(String stderr) {
    String diagnosticSource = stderr == null ? "" : stderr;
    String diagnostic =
        diagnosticSource
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .filter(line -> line.contains("ERROR:"))
            .findFirst()
            .orElse("");
    var match = SQLSTATE_ERROR.matcher(diagnostic);
    if (!match.find()) {
      return "psql: ERROR: unknown [cause=database-error; details=<redacted>]";
    }

    String sqlState = match.group(1).toUpperCase(java.util.Locale.ROOT);
    String detail = redactPsqlDetail(match.group(2));
    String summary =
        "psql: ERROR: "
            + sqlState
            + " [cause="
            + sqlStateCause(sqlState)
            + "]"
            + (detail.isEmpty() ? "" : " detail=" + detail);
    if (summary.length() <= MAX_DIAGNOSTIC_LENGTH) {
      return summary;
    }
    return summary.substring(0, MAX_DIAGNOSTIC_LENGTH - 1) + "…";
  }

  private static String redactPsqlDetail(String detail) {
    String sanitized = TAGGED_DOLLAR_QUOTED_VALUE.matcher(detail).replaceAll("<redacted>");
    sanitized = UNTAGGED_DOLLAR_QUOTED_VALUE.matcher(sanitized).replaceAll("<redacted>");
    sanitized = SINGLE_QUOTED_VALUE.matcher(sanitized).replaceAll("<redacted>");
    sanitized = DOUBLE_QUOTED_VALUE.matcher(sanitized).replaceAll("<redacted>");
    sanitized = FILESYSTEM_PATH.matcher(sanitized).replaceAll("<redacted>");
    sanitized = UUID_VALUE.matcher(sanitized).replaceAll("<redacted>");
    return sanitized.replaceAll("\\s+", " ").strip();
  }

  private static String sqlStateCause(String sqlState) {
    return switch (sqlState.substring(0, 2)) {
      case "2B" -> "dependent-objects";
      case "22" -> "data-exception";
      case "23" -> "integrity-constraint";
      case "28" -> "authorization";
      case "40" -> "transaction-rollback";
      case "42" -> "syntax-or-access-rule";
      case "53" -> "insufficient-resources";
      default -> "database-error";
    };
  }

  private static PostgreSQLContainer createWithScripts(List<Path> initScripts) {
    return createWithScripts(initScripts, POSTGIS_IMAGE);
  }

  private static PostgreSQLContainer createWithScripts(
      List<Path> initScripts, DockerImageName image) {
    requireDocker(() -> DockerClientFactory.instance().isDockerAvailable());

    PostgreSQLContainer container =
        new PostgreSQLContainer(image)
            .withDatabaseName("timing_jeju_repository_test")
            .withUsername("timing_jeju_repository_test")
            .withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofMinutes(3));

    for (int index = 0; index < initScripts.size(); index++) {
      Path script = initScripts.get(index);
      String target =
          "/docker-entrypoint-initdb.d/%03d_%s".formatted(index + 1, script.getFileName());
      container.withCopyFileToContainer(MountableFile.forHostPath(script), target);
    }
    return container;
  }

  static void requireDocker(BooleanSupplier availability) {
    boolean available;
    try {
      available = availability.getAsBoolean();
    } catch (RuntimeException exception) {
      throw new IllegalStateException(DOCKER_UNAVAILABLE_MESSAGE, exception);
    }
    if (!available) {
      throw new IllegalStateException(DOCKER_UNAVAILABLE_MESSAGE);
    }
  }

  static List<Path> canonicalInitScripts(Path repositoryRoot) {
    Path authCompatibility = repositoryRoot.resolve("db/local-postgres/auth_compat.sql");
    Path migrationDirectory = repositoryRoot.resolve("supabase/migrations");
    if (!Files.isRegularFile(authCompatibility)) {
      throw new IllegalStateException(
          "PostgreSQL 테스트용 Auth 호환 SQL을 찾을 수 없습니다: " + authCompatibility);
    }

    List<Path> migrations;
    try (var files = Files.list(migrationDirectory)) {
      migrations =
          files
              .filter(Files::isRegularFile)
              .filter(path -> CANONICAL_MIGRATION.matcher(path.getFileName().toString()).matches())
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Supabase canonical migration을 읽을 수 없습니다: " + migrationDirectory, exception);
    }
    if (migrations.isEmpty()) {
      throw new IllegalStateException("Supabase canonical migration이 없습니다: " + migrationDirectory);
    }
    for (int index = 1; index < migrations.size(); index++) {
      String previous = migrations.get(index - 1).getFileName().toString().substring(0, 14);
      String current = migrations.get(index).getFileName().toString().substring(0, 14);
      if (previous.equals(current)) {
        throw new IllegalStateException("Supabase migration timestamp가 중복됐습니다: " + current);
      }
    }

    List<Path> initScripts = new ArrayList<>(migrations.size() + 1);
    initScripts.add(authCompatibility);
    initScripts.addAll(migrations);
    return List.copyOf(initScripts);
  }

  static Path locateRepositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))
          && Files.isRegularFile(current.resolve("db/local-postgres/auth_compat.sql"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Timing Jeju 저장소 루트를 찾을 수 없습니다.");
  }
}
