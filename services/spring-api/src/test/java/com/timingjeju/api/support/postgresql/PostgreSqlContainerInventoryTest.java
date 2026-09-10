package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PostgreSqlContainerInventoryTest {

  @Test
  void 모든_PostgreSQLContainer_생성은_launcher_session_pool_한곳으로_제한한다() throws Exception {
    Path sources =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("services/spring-api/src/test/java");
    List<String> constructors = new ArrayList<>();
    try (var files = Files.walk(sources)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (!file.getFileName().toString().equals("PostgreSqlContainerInventoryTest.java")
            && Files.readString(file).contains("new PostgreSQL" + "Container(")) {
          constructors.add(sources.relativize(file).toString());
        }
      }
    }

    assertThat(constructors)
        .containsExactly(
            "com/timingjeju/api/support/postgresql/PostgreSqlLauncherSessionPool.java");
  }

  @Test
  void factory와_ServiceConnection은_공통_pool_handle만_반환한다() throws Exception {
    Path support =
        PostgreSqlTestContainerFactory.locateRepositoryRoot()
            .resolve("services/spring-api/src/test/java/com/timingjeju/api/support/postgresql");
    String factory = Files.readString(support.resolve("PostgreSqlTestContainerFactory.java"));
    String configuration =
        Files.readString(support.resolve("PostgreSqlTestcontainersConfiguration.java"));

    assertThat(factory)
        .contains("PostgreSqlLauncherSessionPool.container(")
        .doesNotContain("new PostgreSQLContainer(");
    assertThat(configuration)
        .contains("PostgreSqlTestContainerFactory.create()")
        .doesNotContain("new PostgreSQLContainer(");
  }

  @Test
  void integration_test의_Spring_context와_Hikari_연결_예산을_고정한다() throws Exception {
    Path repository = PostgreSqlTestContainerFactory.locateRepositoryRoot();
    String build = Files.readString(repository.resolve("services/spring-api/build.gradle"));
    String testApplication =
        Files.readString(
            repository.resolve("services/spring-api/src/test/resources/application.yml"));

    assertThat(build).contains("systemProperty 'spring.test.context.cache.maxSize', '24'");
    assertThat(testApplication).contains("maximum-pool-size: 2").contains("minimum-idle: 0");
  }
}
