package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class PlannedAnchorFactsIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired private DataSource dataSource;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"location\":{\"lat\":33.4,\"lng\":126.5}}",
        "{\"nested\":{\"currentLocation\":{\"latitude\":33.4}}}",
        "{\"regionCode\":\"gps-derived-region\"}",
        "{\"nearestPlaceId\":\"gps-derived-place\"}",
        "{\"grid\":{\"nx\":53,\"ny\":38}}",
        "{\"geohash\":\"gps-derived-hash\"}",
        "[]",
        "null"
      })
  void 임의_현재_간접_위치_facts는_DB에서도_거부한다(String facts) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createDraftFixture(connection);
        try (var statement =
            connection.prepareStatement(
                "insert into public.trip_items "
                    + "(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,source,facts) "
                    + "values (?::uuid,?::uuid,?::uuid,1,'custom','위치 없는 계획 메모','user_input',?::jsonb)")) {
          statement.setString(1, fixture.tripPlanId());
          statement.setString(2, fixture.dayId());
          statement.setString(3, fixture.scheduleVersionId());
          statement.setString(4, facts);
          Throwable failure = catchThrowable(statement::executeUpdate);
          assertThat(failure).isInstanceOf(SQLException.class);
          assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514");
          assertThat(failure.getMessage())
              .doesNotContain("33.4", "126.5", "gps-derived", "Failing row");
        }
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void service_role은_빈_facts를_저장하지만_임의_facts_update는_거부된다() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Fixture fixture = createDraftFixture(connection);
        connection.createStatement().execute("set local role service_role");
        try (var insert =
            connection.prepareStatement(
                "insert into public.trip_items "
                    + "(trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,title,source) "
                    + "values (?::uuid,?::uuid,?::uuid,1,'custom','계획 메모','user_input')")) {
          insert.setString(1, fixture.tripPlanId());
          insert.setString(2, fixture.dayId());
          insert.setString(3, fixture.scheduleVersionId());
          assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (var update =
            connection.prepareStatement(
                "update public.trip_items set facts=?::jsonb where schedule_version_id=?::uuid")) {
          update.setString(1, "{\"nested\":{\"geohash\":\"private-marker\"}}");
          update.setString(2, fixture.scheduleVersionId());
          Throwable failure = catchThrowable(update::executeUpdate);
          assertThat(failure).isInstanceOf(SQLException.class);
          assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514");
          assertThat(failure.getMessage()).doesNotContain("private-marker", "Failing row");
        }
      } finally {
        connection.rollback();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"anon", "authenticated", "service_role"})
  void private_facts_trigger는_직접_RPC_EXECUTE를_허용하지_않는다(String role) throws Exception {
    try (Connection connection = dataSource.getConnection();
        var query =
            connection.prepareStatement(
                "select has_function_privilege(?, 'timing_jeju_private.validate_trip_item_closed_facts()', 'EXECUTE')")) {
      query.setString(1, role);
      try (var rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getBoolean(1)).isFalse();
      }
    }
  }

  private static Fixture createDraftFixture(Connection connection) throws SQLException {
    String ownerId = UUID.randomUUID().toString();
    String tripPlanId = UUID.randomUUID().toString();
    String dayId = UUID.randomUUID().toString();
    String scheduleVersionId = UUID.randomUUID().toString();
    String email = ownerId + "@issue225.test";
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
                + "values (?::uuid,?::uuid,?,'ACL QA','draft','2026-09-01','2026-09-01','fixture','issue225-acl',1)")) {
      statement.setString(1, tripPlanId);
      statement.setString(2, ownerId);
      statement.setString(3, "issue225-acl-" + tripPlanId);
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

  private record Fixture(String tripPlanId, String dayId, String scheduleVersionId) {}
}
