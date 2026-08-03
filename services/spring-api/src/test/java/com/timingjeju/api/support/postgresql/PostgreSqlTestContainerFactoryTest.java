package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PostgreSqlTestContainerFactoryTest {

  @Test
  void Docker_daemon을_사용할_수_없으면_한국어로_실패한다() {
    assertThatIllegalStateException()
        .isThrownBy(() -> PostgreSqlTestContainerFactory.requireDocker(() -> false))
        .withMessage(PostgreSqlTestContainerFactory.DOCKER_UNAVAILABLE_MESSAGE);
  }

  @Test
  void Auth_호환_SQL_다음에_timestamp순_canonical_migration만_선택한다() {
    List<Path> scripts =
        PostgreSqlTestContainerFactory.canonicalInitScripts(
            PostgreSqlTestContainerFactory.locateRepositoryRoot());
    List<String> names = scripts.stream().map(path -> path.getFileName().toString()).toList();

    assertThat(names.getFirst()).isEqualTo("auth_compat.sql");
    assertThat(names.subList(1, names.size())).isSorted();
    assertThat(names.subList(1, names.size())).allMatch(name -> name.matches("\\d{14}_.+\\.sql"));
    assertThat(names).doesNotContain("seed_fixtures.sql", "seed.sql");
  }
}
