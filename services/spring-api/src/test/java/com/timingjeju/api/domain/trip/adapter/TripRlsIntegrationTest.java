package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
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
  private static final UUID TRIP = UUID.fromString("44000000-0000-0000-0000-000000000973");
  private static final UUID DAY = UUID.fromString("44000000-0000-0000-0000-000000000975");
  private static final List<String> TRIP_TABLES =
      List.of("trip_plans", "trip_transport_modes", "trip_days");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUpTripAggregate() {
    cleanFixtures();
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", OWNER, "trip-rls-owner@issue44.test");
    jdbc.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER,
        "trip-rls-owner@issue44.test");
    jdbc.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
        values (?, ?, 'issue44-rls-trip', 'original title', current_date, current_date,
                'fixture', 'issue44-rls-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_transport_modes
          (trip_plan_id, transport_mode, priority, is_primary)
        values (?, 'public_transit', 1, true)
        """,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
        values (?, ?, 1, current_date)
        """,
        DAY,
        TRIP);
  }

  @AfterEach
  void cleanFixtures() {
    jdbc.update("delete from public.trip_plans where id = ? or user_id = ?", TRIP, OWNER);
    jdbc.update("delete from public.user_profiles where id = ?", OWNER);
    jdbc.update("delete from auth.users where id = ?", OWNER);
  }

  @Test
  void trip_table은_client_DML을_ACL에서_거부하고_service_role에_최소_DML만_허용한다() throws Exception {
    assertRolePrivilegeMatrix();
    assertNoClientWritePoliciesOrAuthSchemaAccess();
    String expectedXmin =
        jdbc.queryForObject(
            "select xmin::text from public.trip_plans where id = ?", String.class, TRIP);

    for (String role : List.of("anon", "authenticated")) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          execute(connection, "set role " + role);
          assertClientMutationDenied(
              connection,
              """
              insert into public.trip_plans
                (id, user_id, public_token, title, start_date, end_date, source_mode, data_version)
              values (gen_random_uuid(), ?, 'issue44-rls-forged', 'forged', current_date,
                      current_date, 'live', 'issue44-rls-v1')
              """,
              "trip_plans",
              OWNER);
          assertClientMutationDenied(
              connection,
              "update public.trip_plans set title = 'mutated' where id = ?",
              "trip_plans",
              TRIP);
          assertClientMutationDenied(
              connection, "delete from public.trip_plans where id = ?", "trip_plans", TRIP);
          assertClientMutationDenied(
              connection,
              """
              insert into public.trip_transport_modes
                (trip_plan_id, transport_mode, priority, is_primary)
              values (?, 'rental_car', 2, false)
              """,
              "trip_transport_modes",
              TRIP);
          assertClientMutationDenied(
              connection,
              """
              update public.trip_transport_modes
              set priority = 2
              where trip_plan_id = ? and transport_mode = 'public_transit'
              """,
              "trip_transport_modes",
              TRIP);
          assertClientMutationDenied(
              connection,
              """
              delete from public.trip_transport_modes
              where trip_plan_id = ? and transport_mode = 'public_transit'
              """,
              "trip_transport_modes",
              TRIP);
          assertClientMutationDenied(
              connection,
              """
              insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
              values (gen_random_uuid(), ?, 2, current_date + 1)
              """,
              "trip_days",
              TRIP);
          assertClientMutationDenied(
              connection,
              "update public.trip_days set title = 'mutated' where id = ?",
              "trip_days",
              DAY);
          assertClientMutationDenied(
              connection, "delete from public.trip_days where id = ?", "trip_days", DAY);
        } finally {
          connection.rollback();
          execute(connection, "reset role");
        }
      }
      assertTripAggregateUnchanged(expectedXmin);
    }
  }

  private void assertRolePrivilegeMatrix() {
    for (String table : TRIP_TABLES) {
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("anon", table, privilege, false);
        assertPrivilege("authenticated", table, privilege, false);
        assertPrivilege("service_role", table, privilege, true);
      }
      for (String role : List.of("anon", "authenticated")) {
        assertPrivilege(role, table, "TRUNCATE", false);
      }
      for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
        assertPrivilege("service_role", table, privilege, false);
      }
    }
  }

  private void assertNoClientWritePoliciesOrAuthSchemaAccess() {
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from pg_policies
                where schemaname = 'public'
                  and tablename in ('trip_plans', 'trip_transport_modes', 'trip_days')
                  and cmd in ('INSERT', 'UPDATE', 'DELETE')
                """,
                Integer.class))
        .isZero();
    for (String role : List.of("anon", "authenticated")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_schema_privilege(?, 'auth', 'USAGE')", Boolean.class, role))
          .as("%s must not access auth schema", role)
          .isFalse();
    }
  }

  private void assertPrivilege(String role, String table, String privilege, boolean expected) {
    assertThat(
            jdbc.queryForObject(
                "select has_table_privilege(?, 'public.' || ?, ?)",
                Boolean.class,
                role,
                table,
                privilege))
        .as("%s %s on %s", role, privilege, table)
        .isEqualTo(expected);
  }

  private static void assertClientMutationDenied(
      Connection connection, String sql, String table, Object... parameters) throws SQLException {
    Savepoint savepoint = connection.setSavepoint();
    SQLException failure =
        catchThrowableOfType(SQLException.class, () -> execute(connection, sql, parameters));
    connection.rollback(savepoint);

    assertThat((Throwable) failure).isExactlyInstanceOf(PSQLException.class);
    assertThat(failure.getSQLState()).isEqualTo("42501");
    assertThat(((PSQLException) failure).getServerErrorMessage().getMessage())
        .isEqualTo("permission denied for table " + table);
  }

  private void assertTripAggregateUnchanged(String expectedXmin) {
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from public.trip_plans
                where id = ? and user_id = ? and title = 'original title' and xmin::text = ?
                """,
                Integer.class,
                TRIP,
                OWNER,
                expectedXmin))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_plans where user_id = ?", Integer.class, OWNER))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_days where trip_plan_id = ?",
                Integer.class,
                TRIP))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.trip_transport_modes where trip_plan_id = ?",
                Integer.class,
                TRIP))
        .isOne();
  }

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      statement.execute();
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }
}
