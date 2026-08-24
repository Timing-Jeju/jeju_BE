package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripRlsIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000971");
  private static final UUID OTHER = UUID.fromString("44000000-0000-0000-0000-000000000972");
  private static final UUID OTHER_TRIP = UUID.fromString("44000000-0000-0000-0000-000000000973");
  private static final Set<String> TRIGGER_ORDERED_CHILD_DENIAL_STATES = Set.of("42501", "P0001");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUpOwnersAndOtherTrip() {
    cleanFixtures();
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?), (?, ?)",
        OWNER,
        "trip-rls-owner@issue44.test",
        OTHER,
        "trip-rls-other@issue44.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?), (?, ?)",
        OWNER,
        "trip-rls-owner@issue44.test",
        OTHER,
        "trip-rls-other@issue44.test");
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
        values (?, ?, 'issue44-rls-other', 'other', current_date, current_date,
                'fixture', 'issue44-rls-v1')
        """,
        OTHER_TRIP,
        OTHER);
  }

  @AfterEach
  void cleanFixtures() {
    jdbc.update("delete from public.trip_plans where user_id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", OWNER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", OWNER, OTHER);
  }

  @Test
  void transaction_local_임시_GRANT에서_RLS는_owner만_허용하고_rollback후_ACL은_닫힌다() throws Exception {
    UUID ownTrip = UUID.fromString("44000000-0000-0000-0000-000000000974");
    UUID ownDay = UUID.fromString("44000000-0000-0000-0000-000000000975");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        String otherTripXmin =
            queryString(
                connection, "select xmin::text from public.trip_plans where id = ?", OTHER_TRIP);
        execute(
            connection,
            "grant select, insert, update, delete on table public.trip_plans, public.trip_transport_modes, public.trip_days to authenticated");
        execute(
            connection,
            "grant select on table public.trip_schedule_versions, public.trip_items to authenticated");
        execute(connection, "grant usage on schema auth to authenticated");
        execute(connection, "grant execute on function auth.uid() to authenticated");
        execute(
            connection, "select set_config('request.jwt.claim.sub', ?, false)", OWNER.toString());
        execute(connection, "set role authenticated");
        execute(
            connection,
            """
            insert into public.trip_plans
              (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
            values (?, ?, 'issue44-rls-own', 'own', current_date, current_date,
                    'live', 'issue44-rls-v1')
            """,
            ownTrip,
            OWNER);
        execute(
            connection,
            "insert into public.trip_transport_modes (trip_plan_id, transport_mode, priority, is_primary) values (?, 'public_transit', 1, true)",
            ownTrip);
        execute(
            connection,
            "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (?, ?, 1, current_date)",
            ownDay,
            ownTrip);
        String ownTripXmin =
            queryString(
                connection, "select xmin::text from public.trip_plans where id = ?", ownTrip);

        assertRlsDenied(
            connection,
            """
            insert into public.trip_plans
              (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
            values (gen_random_uuid(), ?, 'issue44-rls-forged', 'forged', current_date,
                    current_date, 'live', 'issue44-rls-v1')
            """,
            OTHER);
        assertRlsDenied(
            connection,
            "insert into public.trip_transport_modes (trip_plan_id, transport_mode, priority, is_primary) values (?, 'public_transit', 1, true)",
            OTHER_TRIP);
        assertTriggerOrderedChildInsertDenied(
            connection,
            "insert into public.trip_days (id, trip_plan_id, day_no, trip_date) values (gen_random_uuid(), ?, 1, current_date)",
            OTHER_TRIP);
        execute(connection, "reset role");
        assertOtherTripUnchanged(connection, otherTripXmin);
        execute(connection, "set role authenticated");
        assertRlsDenied(
            connection, "update public.trip_plans set user_id = ? where id = ?", OTHER, ownTrip);
        assertRlsDenied(
            connection,
            "update public.trip_transport_modes set trip_plan_id = ? where trip_plan_id = ? and transport_mode = 'public_transit'",
            OTHER_TRIP,
            ownTrip);
        assertTriggerOrderedChildUpdateDenied(
            connection,
            "update public.trip_days set trip_plan_id = ? where id = ?",
            OTHER_TRIP,
            ownDay);
        execute(connection, "reset role");
        assertTripDayAndRootsUnchanged(connection, ownTrip, ownDay, ownTripXmin, otherTripXmin);
        execute(connection, "set role authenticated");
        assertThat(
                executeUpdate(
                    connection,
                    "update public.trip_plans set title = 'cross-owner' where id = ?",
                    OTHER_TRIP))
            .isZero();
      } finally {
        connection.rollback();
        execute(connection, "reset role");
        execute(connection, "select set_config('request.jwt.claim.sub', '', false)");
      }
    }
    for (String table : List.of("trip_plans", "trip_transport_modes", "trip_days")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('authenticated', 'public.' || ?, 'SELECT,INSERT,UPDATE,DELETE')",
                  Boolean.class,
                  table))
          .isFalse();
    }
    for (String table : List.of("trip_schedule_versions", "trip_items")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_table_privilege('authenticated', 'public.' || ?, 'SELECT')",
                  Boolean.class,
                  table))
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select has_schema_privilege('authenticated', 'auth', 'USAGE')", Boolean.class))
        .isFalse();
  }

  private static void assertRlsDenied(Connection connection, String sql, Object... parameters)
      throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    SQLException failure =
        catchThrowableOfType(SQLException.class, () -> execute(connection, sql, parameters));
    connection.rollback(savepoint);
    assertThat(failure.getSQLState()).isEqualTo("42501");
  }

  private static void assertTriggerOrderedChildInsertDenied(
      Connection connection, String sql, Object... parameters) throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    SQLException failure =
        catchThrowableOfType(SQLException.class, () -> execute(connection, sql, parameters));
    connection.rollback(savepoint);

    assertThat(TRIGGER_ORDERED_CHILD_DENIAL_STATES).contains(failure.getSQLState());
    if ("P0001".equals(failure.getSQLState())) {
      assertThat(failure.getClass()).isEqualTo(PSQLException.class);
      String primaryMessage = ((PSQLException) failure).getServerErrorMessage().getMessage();
      String sanitizedMessage = primaryMessage.replace(OTHER_TRIP.toString(), "<trip-id>");
      assertThat(sanitizedMessage)
          .isEqualTo("trip plan <trip-id> does not exist")
          .doesNotContain(OTHER.toString());
    }
  }

  private static void assertTriggerOrderedChildUpdateDenied(
      Connection connection, String sql, Object... parameters) throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    SQLException failure =
        catchThrowableOfType(SQLException.class, () -> execute(connection, sql, parameters));
    connection.rollback(savepoint);

    assertThat(failure.getSQLState()).isEqualTo("P0001");
    assertThat(failure.getClass()).isEqualTo(PSQLException.class);
    String primaryMessage = ((PSQLException) failure).getServerErrorMessage().getMessage();
    String sanitizedMessage = primaryMessage.replace(OTHER_TRIP.toString(), "<trip-id>");
    assertThat(sanitizedMessage)
        .isEqualTo("trip plan <trip-id> does not exist")
        .doesNotContain(OTHER.toString());
  }

  private static void assertOtherTripUnchanged(Connection connection, String expectedXmin)
      throws SQLException {
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_days where trip_plan_id = ?",
                OTHER_TRIP))
        .isZero();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_transport_modes where trip_plan_id = ?",
                OTHER_TRIP))
        .isZero();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_plans where id = ? and user_id = ? and title = 'other' and xmin::text = ?",
                OTHER_TRIP,
                OTHER,
                expectedXmin))
        .isOne();
  }

  private static void assertTripDayAndRootsUnchanged(
      Connection connection, UUID ownTrip, UUID ownDay, String ownTripXmin, String otherTripXmin)
      throws SQLException {
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_days where id = ? and trip_plan_id = ?",
                ownDay,
                ownTrip))
        .isOne();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_days where trip_plan_id = ?",
                OTHER_TRIP))
        .isZero();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_transport_modes where trip_plan_id = ?",
                OTHER_TRIP))
        .isZero();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_plans where id = ? and user_id = ? and title = 'own' and xmin::text = ?",
                ownTrip,
                OWNER,
                ownTripXmin))
        .isOne();
    assertThat(
            queryInt(
                connection,
                "select count(*) from public.trip_plans where id = ? and user_id = ? and title = 'other' and xmin::text = ?",
                OTHER_TRIP,
                OTHER,
                otherTripXmin))
        .isOne();
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.execute();
    }
  }

  private static int executeUpdate(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    }
  }

  private static int queryInt(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (java.sql.ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getInt(1);
      }
    }
  }

  private static String queryString(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (java.sql.ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }
}
