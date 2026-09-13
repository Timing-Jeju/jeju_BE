package com.timingjeju.api.support.postgresql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
  private static final Map<String, String> DATA_DIRECTORY_TMPFS =
      Map.of("/var/lib/postgresql/data", "rw,noexec,nosuid,size=2g");

  private PostgreSqlTestContainerFactory() {}

  static PostgreSQLContainer create() {
    return createWithScripts(canonicalInitScripts(locateRepositoryRoot()));
  }

  static PostgreSQLContainer create(String image) {
    if (!List.of("postgis/postgis:16-3.4", "postgis/postgis:17-3.5").contains(image)) {
      throw new IllegalArgumentException("검증한 PostgreSQL 16/17 이미지가 필요합니다.");
    }
    return createWithScripts(
        canonicalInitScripts(locateRepositoryRoot()),
        DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
  }

  static PostgreSQLContainer createBefore(String exclusiveMigration) {
    return createBefore(exclusiveMigration, POSTGIS_IMAGE.asCanonicalNameString());
  }

  static PostgreSQLContainer createBefore(String exclusiveMigration, String image) {
    List<Path> scripts = canonicalInitScripts(locateRepositoryRoot());
    return createWithScripts(
        scripts.subList(0, targetIndex(scripts, exclusiveMigration)),
        DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
  }

  static PostgreSQLContainer createHistoricalCutoverPredecessor(
      String exclusiveMigration, String image) {
    List<Path> scripts = canonicalInitScripts(locateRepositoryRoot());
    return PostgreSqlLauncherSessionPool.historicalCutoverContainer(
        DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"),
        scripts.subList(0, targetIndex(scripts, exclusiveMigration)));
  }

  static void executeScript(PostgreSQLContainer container, Path script) throws Exception {
    executeScript(container, container.getDatabaseName(), script);
  }

  static void executeScript(PostgreSqlImageFixturePool.IsolatedDatabase database, Path script)
      throws Exception {
    database.executeScript(script);
  }

  static void executeScript(PostgreSQLContainer container, String databaseName, Path script)
      throws Exception {
    String target = "/tmp/" + UUID.randomUUID() + "_" + script.getFileName();
    container.copyFileToContainer(MountableFile.forHostPath(script), target);
    var result =
        container.execInContainer(
            "psql",
            "--no-psqlrc",
            "--set",
            "ON_ERROR_STOP=1",
            "--username",
            container.getUsername(),
            "--dbname",
            databaseName,
            "--file",
            target);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          "PostgreSQL script 실행 실패: "
              + script.getFileName()
              + " (exit="
              + result.getExitCode()
              + ", stdout="
              + result.getStdout()
              + ", stderr="
              + result.getStderr()
              + ")");
    }
  }

  private static PostgreSQLContainer createWithScripts(List<Path> initScripts) {
    return createWithScripts(initScripts, POSTGIS_IMAGE);
  }

  private static PostgreSQLContainer createWithScripts(
      List<Path> initScripts, DockerImageName image) {
    requireDocker(() -> DockerClientFactory.instance().isDockerAvailable());

    return PostgreSqlLauncherSessionPool.container(image, initScripts);
  }

  private static int targetIndex(List<Path> scripts, String exclusiveMigration) {
    for (int index = 0; index < scripts.size(); index++) {
      String fileName = scripts.get(index).getFileName().toString();
      if (exclusiveMigration.equals(fileName)
          || (exclusiveMigration.equals("20260918000017_user_location_write_guard_purge.sql")
              && fileName.equals("20260918000017_location_cutover_group.sql"))) {
        return index;
      }
    }
    throw new IllegalStateException("대상 Supabase migration이 없습니다: " + exclusiveMigration);
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

  static Map<String, String> dataDirectoryTmpFs() {
    return DATA_DIRECTORY_TMPFS;
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
    for (Path migration : migrations) {
      String name = migration.getFileName().toString();
      if (name.equals("20260918000018_revision_request_hash_audit.sql")) continue;
      if (name.equals("20260918000017_user_location_write_guard_purge.sql")) {
        initScripts.add(
            repositoryRoot.resolve("db/local-postgres/20260918000017_location_cutover_group.sql"));
      } else {
        initScripts.add(migration);
      }
    }
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
