package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("integration")
class LocationPurgeLockConcurrencyIntegrationTest {
  private static final String TARGET = "20260918000017_user_location_write_guard_purge.sql";

  @ParameterizedTest
  @CsvSource({
    "postgis/postgis:16-3.4,mcp", "postgis/postgis:17-3.5,mcp",
    "postgis/postgis:16-3.4,preferences", "postgis/postgis:17-3.5,preferences",
    "postgis/postgis:16-3.4,idempotency", "postgis/postgis:17-3.5,idempotency"
  })
  void 초기_감사_잠금은_다른_세션의_MCP와_JSON_쓰기를_차단하고_rollback_후_해제한다(String image, String target)
      throws Exception {
    var container = PostgreSqlTestContainerFactory.createBefore(TARGET, image);
    try {
      container.start();
      var root = PostgreSqlTestContainerFactory.locateRepositoryRoot();
      String migration =
          Files.readString(PostgreSqlTestContainerFactory.canonicalMigrationPath(root, TARGET));
      var lock =
          Pattern.compile("(?s)lock table\\s+.*?in access exclusive mode;").matcher(migration);
      assertThat(lock.find()).isTrue();
      // Empty fixtures isolate relation-lock behavior from row/FK/trigger validation.
      String update =
          switch (target) {
            case "mcp" ->
                "update public.mcp_compute_call_logs set latency_ms=latency_ms where false";
            case "preferences" ->
                "update public.trip_preferences set raw_answers=raw_answers where false";
            case "idempotency" ->
                "update public.api_idempotency_records set request_hash=request_hash where false";
            default -> throw new IllegalArgumentException("unsupported fixture");
          };
      try (var audit =
              DriverManager.getConnection(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword());
          var writer =
              DriverManager.getConnection(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword());
          var auditStatement = audit.createStatement();
          var writerStatement = writer.createStatement()) {
        audit.setAutoCommit(false);
        try {
          auditStatement.execute(lock.group());
          writerStatement.execute("set lock_timeout='500ms'");
          assertThatThrownBy(() -> writerStatement.executeUpdate(update))
              .isInstanceOf(SQLException.class)
              .satisfies(
                  failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("55P03"));
        } finally {
          audit.rollback();
        }
        assertThat(writerStatement.executeUpdate(update)).isZero();
      }
    } finally {
      container.stop();
    }
  }
}
