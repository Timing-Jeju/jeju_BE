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
    assertThat(names.subList(1, names.size()).stream().map(name -> name.substring(0, 14)))
        .doesNotHaveDuplicates();
    assertThat(names).doesNotContain("seed_fixtures.sql", "seed.sql");
  }

  @Test
  void psql_진단은_인용값_path_UUID를_형식과무관하게_가리고_SQLSTATE_원인범주를_유지한다() throws Exception {
    String containerTarget = "/tmp/12345678-1234-1234-1234-123456789abc_contract.sql";
    String stderr =
        """
        psql:/Users/private/account.sql:9: NOTICE: ignored
        psql:%s:160: ERROR:  2BP01 owner 'single-secret' identifier "double-secret" \
        $$dollar-secret$$ $token$tagged-dollar-secret$token$ \
        /Users/private/customer.csv 87654321-4321-4321-4321-cba987654321
        """
            .formatted(containerTarget);

    String summary = PostgreSqlTestContainerFactory.safePsqlErrorSummary(stderr);

    assertThat(summary)
        .contains("psql: ERROR: 2BP01", "cause=dependent-objects", "<redacted>")
        .doesNotContain(
            "single-secret",
            "double-secret",
            "dollar-secret",
            "tagged-dollar-secret",
            "/Users/private",
            "12345678-1234-1234-1234-123456789abc",
            "87654321-4321-4321-4321-cba987654321");
  }

  @Test
  void 긴_psql_진단도_512자안에서_SQLSTATE와_원인범주를_앞에_보존한다() throws Exception {
    String longCause = "permission denied ".repeat(50);
    String stderr =
        "psql:/tmp/contract.sql:160: ERROR: 42501 " + longCause + "\"sensitive-trailing-value\"";

    String summary = PostgreSqlTestContainerFactory.safePsqlErrorSummary(stderr);

    assertThat(summary)
        .hasSizeLessThanOrEqualTo(512)
        .startsWith("psql: ERROR: 42501 [cause=syntax-or-access-rule]")
        .endsWith("…")
        .doesNotContain("sensitive");
  }

  @Test
  void SQLSTATE가_없는_stderr는_원문을_반사하지않고_generic오류로_닫힌다() {
    String summary =
        PostgreSqlTestContainerFactory.safePsqlErrorSummary(
            "psql:/private/customer.sql: ERROR: secret-without-sqlstate");

    assertThat(summary)
        .isEqualTo("psql: ERROR: unknown [cause=database-error; details=<redacted>]")
        .doesNotContain("secret-without-sqlstate", "/private/customer.sql");
  }
}
