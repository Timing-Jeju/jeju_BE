package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleItemRequiredReferencesMigrationIntegrationTest
    extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final String MIGRATION =
      "20260918000008_schedule_item_required_references_correction.sql";

  @Autowired private DataSource dataSource;

  @Test
  void helper_ACL은_client_RPC를_거부하고_service_role의_assertion만_허용한다() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(
              hasPublicExecutePrivilege(
                  connection, "public.validate_trip_item_required_references()"))
          .as("PUBLIC trigger helper")
          .isFalse();
      for (String role : List.of("anon", "authenticated", "service_role")) {
        assertThat(
                hasExecutePrivilege(
                    connection, role, "public.validate_trip_item_required_references()"))
            .as("%s trigger helper", role)
            .isFalse();
      }
      assertThat(
              hasPublicExecutePrivilege(
                  connection, "public.assert_schedule_item_required_references(uuid,uuid)"))
          .as("PUBLIC sealing assertion")
          .isFalse();
      for (String role : List.of("anon", "authenticated")) {
        assertThat(
                hasExecutePrivilege(
                    connection, role, "public.assert_schedule_item_required_references(uuid,uuid)"))
            .as("%s sealing assertion", role)
            .isFalse();
      }
      assertThat(
              hasExecutePrivilege(
                  connection,
                  "service_role",
                  "public.assert_schedule_item_required_references(uuid,uuid)"))
          .isTrue();
    }
  }

  @Test
  void service_role_정상쓰기는_EXECUTE가_없는_trigger_helper를_자연호출하고_sealing도_통과한다() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createDraftFixture(connection);
        String itemId = UUID.randomUUID().toString();

        connection.createStatement().execute("set local role service_role");
        try (var insert =
            connection.prepareStatement(
                "insert into public.trip_items "
                    + "(id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,source) "
                    + "values (?::uuid,?::uuid,?::uuid,?::uuid,1,'custom','ACL 실제 변경','user_input')")) {
          insert.setString(1, itemId);
          insert.setString(2, fixture.tripPlanId());
          insert.setString(3, fixture.dayId());
          insert.setString(4, fixture.scheduleVersionId());
          assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (var stored =
            connection.prepareStatement(
                "select item_type,title from public.trip_items where id=?::uuid")) {
          stored.setString(1, itemId);
          try (var rows = stored.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("custom");
            assertThat(rows.getString(2)).isEqualTo("ACL 실제 변경");
          }
        }
        try (var assertion =
            connection.prepareStatement(
                "select public.assert_schedule_item_required_references(?::uuid, ?::uuid)")) {
          assertion.setString(1, fixture.scheduleVersionId());
          assertion.setString(2, fixture.tripPlanId());
          assertion.execute();
        }
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void anon과_authenticated의_sealing_assertion_직접호출은_42501로_거부된다() throws Exception {
    for (String role : List.of("anon", "authenticated")) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          connection.createStatement().execute("set local role " + role);
          Throwable failure =
              catchThrowable(
                  () ->
                      connection
                          .createStatement()
                          .execute(
                              "select public.assert_schedule_item_required_references("
                                  + "'00000000-0000-0000-0000-000000000001'::uuid,"
                                  + "'00000000-0000-0000-0000-000000000002'::uuid)"));
          assertThat(sqlFailure(failure).getSQLState()).as(role).isEqualTo("42501");
        } finally {
          connection.rollback();
        }
      }
    }
  }

  @Test
  void invalid_legacy_item은_23514와_item_id로_migration_전체를_fail_closed한다() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createDraftFixture(connection);
        String itemId = UUID.randomUUID().toString();
        insertValidItem(connection, fixture, itemId);
        revertInvariant(connection);
        connection
            .createStatement()
            .execute(
                """
                update public.trip_items
                set item_type='accommodation', accommodation_id=null, transport_event_id=null
                where id='%s'::uuid
                """
                    .formatted(itemId));

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

  private static Fixture createDraftFixture(Connection connection) throws SQLException {
    String ownerId = UUID.randomUUID().toString();
    String tripPlanId = UUID.randomUUID().toString();
    String dayId = UUID.randomUUID().toString();
    String scheduleVersionId = UUID.randomUUID().toString();
    String email = ownerId + "@issue50.test";
    try (var statement =
        connection.prepareStatement("insert into auth.users(id,email) values (?::uuid,?)")) {
      statement.setString(1, ownerId);
      statement.setString(2, email);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.user_profiles(id,email) values (?::uuid,?)")) {
      statement.setString(1, ownerId);
      statement.setString(2, email);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_plans "
                + "(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision) "
                + "values (?::uuid,?::uuid,?,'ACL QA','draft','2026-09-01','2026-09-01','fixture','issue50-acl',1)")) {
      statement.setString(1, tripPlanId);
      statement.setString(2, ownerId);
      statement.setString(3, "issue50-acl-" + tripPlanId);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (?::uuid,?::uuid,1,'2026-09-01')")) {
      statement.setString(1, dayId);
      statement.setString(2, tripPlanId);
      statement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type,summary) values (?::uuid,?::uuid,1,'draft','user_edit','ACL trigger QA')")) {
      statement.setString(1, scheduleVersionId);
      statement.setString(2, tripPlanId);
      statement.executeUpdate();
    }
    return new Fixture(tripPlanId, dayId, scheduleVersionId);
  }

  private static void insertValidItem(Connection connection, Fixture fixture, String itemId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "insert into public.trip_items (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,source) values (?::uuid,?::uuid,?::uuid,?::uuid,1,'custom','legacy audit QA','user_input')")) {
      statement.setString(1, itemId);
      statement.setString(2, fixture.tripPlanId());
      statement.setString(3, fixture.dayId());
      statement.setString(4, fixture.scheduleVersionId());
      statement.executeUpdate();
    }
  }

  private static void revertInvariant(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("drop trigger trg_trip_items_required_references on public.trip_items");
      statement.execute(
          "alter table public.trip_items drop constraint chk_trip_items_required_references");
    }
  }

  private static void executeMigration(Connection connection) throws Exception {
    connection.createStatement().execute(Files.readString(locateMigration()));
  }

  private static boolean hasExecutePrivilege(Connection connection, String role, String signature)
      throws SQLException {
    try (var statement =
        connection.prepareStatement("select has_function_privilege(?, ?, 'EXECUTE')")) {
      statement.setString(1, role);
      statement.setString(2, signature);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
  }

  private static boolean hasPublicExecutePrivilege(Connection connection, String signature)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "select exists (select 1 from pg_catalog.aclexplode("
                + "coalesce(procedure.proacl, pg_catalog.acldefault('f', procedure.proowner))) acl "
                + "where acl.grantee=0 and acl.privilege_type='EXECUTE') "
                + "from pg_catalog.pg_proc procedure where procedure.oid=?::regprocedure")) {
      statement.setString(1, signature);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getBoolean(1);
      }
    }
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

  private record Fixture(String tripPlanId, String dayId, String scheduleVersionId) {}
}
