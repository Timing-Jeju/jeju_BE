package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleItemRequiredReferencesMigrationIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final String MIGRATION = "20260907000001_schedule_item_required_references.sql";

  @Autowired private DataSource dataSource;

  @Test
  void invalid_legacy_item은_23514와_item_id로_migration_전체를_fail_closed한다() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        revertInvariant(connection);
        connection
            .createStatement()
            .execute(
                """
                update public.trip_items
                set item_type='accommodation', accommodation_id=null, transport_event_id=null
                where id=(select id from public.trip_items order by id limit 1)
                """);
        String itemId;
        try (var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "select id::text from public.trip_items order by id limit 1")) {
          assertThat(rows.next()).isTrue();
          itemId = rows.getString(1);
        }

        Throwable failure = catchThrowable(() -> executeMigration(connection));

        SQLException sqlFailure = sqlFailure(failure);
        assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
        assertThat(sqlFailure.getMessage())
            .contains("legacy schedule item required reference audit failed")
            .contains(itemId);
      } finally {
        connection.rollback();
      }
    }
  }

  private static void revertInvariant(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "drop trigger trg_validate_trip_item_required_references on public.trip_items");
      statement.execute(
          "alter table public.trip_items drop constraint trip_items_required_references_by_type");
    }
  }

  private static void executeMigration(Connection connection) throws Exception {
    connection.createStatement().execute(Files.readString(locateMigration()));
  }

  private static Path locateMigration() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      Path migration = current.resolve("supabase/migrations").resolve(MIGRATION);
      if (Files.isRegularFile(migration)) {
        return migration;
      }
      current = current.getParent();
    }
    throw new AssertionError("Issue #50 required reference migration을 찾을 수 없습니다.");
  }

  private static SQLException sqlFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      current = current.getCause();
    }
    throw new AssertionError("SQLState를 가진 migration failure가 아닙니다.", failure);
  }
}
