package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
class TripPreferencesMigrationIntegrationTest {
  private static final String TARGET = "20260907000003_trip_preferences_replace_contract.sql";
  private static final UUID OWNER = UUID.fromString("46200000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("46200000-0000-0000-0000-000000000002");
  private static final UUID TRIP = UUID.fromString("46200000-0000-0000-0000-000000000046");
  private static final UUID OTHER_TRIP = UUID.fromString("46200000-0000-0000-0000-000000000047");
  private static final UUID LEGACY_MODE_ONLY =
      UUID.fromString("46200000-0000-0000-0000-000000000048");
  private static final UUID MODE_ONLY = UUID.fromString("46200000-0000-0000-0000-000000000049");
  private static final UUID MODE_TARGET = UUID.fromString("46200000-0000-0000-0000-000000000050");
  private static final UUID PREFERENCE_ONLY =
      UUID.fromString("46200000-0000-0000-0000-000000000051");
  private static final UUID CASCADE = UUID.fromString("46200000-0000-0000-0000-000000000052");
  private static PostgreSQLContainer container;
  private static DriverManagerDataSource dataSource;
  private static JdbcTemplate jdbc;
  private static int policiesBefore;

  @BeforeAll
  static void startAtPreviousSchemaWithInvalidLegacyRows() {
    container = PostgreSqlTestContainerFactory.createBefore(TARGET);
    container.start();
    dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    policiesBefore = policyCount();
    jdbc.update("insert into auth.users(id,email) values (?,?)", OWNER, "owner@issue46.test");
    jdbc.update("insert into auth.users(id,email) values (?,?)", OTHER, "other@issue46.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", OWNER, "owner@issue46.test");
    jdbc.update(
        "insert into public.user_profiles(id,email) values (?,?)", OTHER, "other@issue46.test");
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
           source_mode,data_version)
        values (?,?,'issue46-migration','Issue 46','draft',date '2026-09-01',date '2026-09-03',
                'Asia/Seoul','normal','fixture','issue46-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
           source_mode,data_version)
        values (?,?,'issue46-migration-other','Issue 46 other','draft',date '2026-09-01',
                date '2026-09-03','Asia/Seoul','normal','fixture','issue46-v1')
        """,
        OTHER_TRIP,
        OTHER);
    for (UUID tripId :
        List.of(LEGACY_MODE_ONLY, MODE_ONLY, MODE_TARGET, PREFERENCE_ONLY, CASCADE)) {
      insertTrip(tripId, OWNER);
    }
    jdbc.update(
        "insert into public.trip_transport_modes"
            + " (trip_plan_id,transport_mode,priority,is_primary) values"
            + " (?,'public_transit',1,true)",
        LEGACY_MODE_ONLY);
    jdbc.update(
        """
        insert into public.trip_preferences
          (trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,
           preferred_region_codes,raw_answers)
        values (?,array['cafe'],'jeju-si','seogwipo-si',array[]::text[],'{}')
        """,
        TRIP);
    jdbc.update(
        """
        insert into public.trip_transport_modes
          (trip_plan_id,transport_mode,priority,is_primary)
        values (?,'public_transit',1,false),(?,'taxi',2,false)
        """,
        TRIP,
        TRIP);
  }

  @AfterAll
  static void stop() {
    if (container != null) container.stop();
  }

  @Test
  @Order(1)
  void invalid_legacy_mode_set은_migration을fail_closed하고원본rows와ACL을보존한다() {
    Path target = target();
    List<java.util.Map<String, Object>> before = modes();
    assertThat(before).extracting(row -> row.get("is_primary")).containsExactly(false, false);

    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);

    assertThat(modes()).isEqualTo(before);
    assertThat(policyCount()).isEqualTo(policiesBefore);
    assertThat(
            jdbc.queryForObject(
                "select to_regprocedure('public.validate_trip_transport_mode_set()') is null",
                Boolean.class))
        .isTrue();

    jdbc.update(
        "update public.trip_transport_modes set is_primary=true where trip_plan_id=? and"
            + " priority=1",
        TRIP);
    jdbc.update(
        "update public.trip_preferences set departure_region_code=E'\\t\\nseogwipo-si\\013' where"
            + " trip_plan_id=?",
        TRIP);
    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update(
        "update public.trip_preferences set departure_region_code='seogwipo-si' where"
            + " trip_plan_id=?",
        TRIP);

    jdbc.update(
        "update public.trip_transport_modes set priority=3 where trip_plan_id=? and"
            + " transport_mode='taxi'",
        TRIP);
    assertThatThrownBy(() -> PostgreSqlTestContainerFactory.executeScript(container, target))
        .isInstanceOf(IllegalStateException.class);
    jdbc.update(
        "update public.trip_transport_modes set priority=2 where trip_plan_id=? and"
            + " transport_mode='taxi'",
        TRIP);
  }

  @Test
  @Order(2)
  void valid_legacy_fix후_apply는_real_roles_RLS와_exact_least_privilege를고정한다() throws Exception {
    PostgreSqlTestContainerFactory.executeScript(container, target());

    assertThat(
            jdbc.queryForList(
                "select rolname from pg_roles where rolname in"
                    + " ('anon','authenticated','service_role')",
                String.class))
        .containsExactlyInAnyOrder("anon", "authenticated", "service_role");
    assertThat(policyCount()).isEqualTo(policiesBefore);
    for (String table : List.of("trip_preferences", "trip_transport_modes")) {
      assertPrivilege("anon", table, "SELECT", false);
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("service_role", table, privilege, true);
      }
      for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
        assertPrivilege("service_role", table, privilege, false);
      }
      assertPrivilege("authenticated", table, "SELECT", true);
      for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("authenticated", table, privilege, false);
      }
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_policies where schemaname='public' and tablename in"
                    + " ('trip_preferences','trip_transport_modes') and roles @>"
                    + " array['authenticated']::name[] and cmd='SELECT' and qual like"
                    + " '%owns_trip_plan%'",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select"
                    + " has_function_privilege('anon','public.validate_trip_transport_mode_set()','EXECUTE')",
                Boolean.class))
        .isFalse();
    for (String role : List.of("anon", "authenticated")) {
      assertThat(
              jdbc.queryForObject(
                  "select has_function_privilege(?,"
                      + " 'public.trip_preference_ascii_trim(text)','EXECUTE')",
                  Boolean.class,
                  role))
          .isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select has_function_privilege('service_role',"
                    + " 'public.trip_preference_ascii_trim(text)','EXECUTE')",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select provolatile='i' and proisstrict and proparallel='s' and not prosecdef"
                    + " and proconfig=array['search_path=pg_catalog, public']::text[]"
                    + " from pg_proc where"
                    + " oid='public.trip_preference_ascii_trim(text)'::regprocedure",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select array_to_string(proconfig,',') from pg_proc where"
                    + " oid='public.validate_trip_transport_mode_set()'::regprocedure",
                String.class))
        .contains("search_path=pg_catalog, public");

    for (String table : List.of("trip_preferences", "trip_transport_modes")) {
      assertSqlDenied("anon", null, "select count(*) from public." + table);
    }
    withRole(
        "authenticated",
        OWNER,
        connection -> {
          assertThat(
                  queryCount(
                      connection,
                      "select count(*) from public.trip_preferences where trip_plan_id=?",
                      TRIP))
              .isOne();
          assertThat(
                  queryCount(
                      connection,
                      "select count(*) from public.trip_transport_modes where trip_plan_id=?",
                      TRIP))
              .isEqualTo(2);
        });
    withRole(
        "authenticated",
        OTHER,
        connection -> {
          assertThat(queryCount(connection, "select count(*) from public.trip_preferences"))
              .isZero();
          assertThat(queryCount(connection, "select count(*) from public.trip_transport_modes"))
              .isZero();
        });
    assertSqlDenied(
        "authenticated",
        OWNER,
        "insert into public.trip_preferences"
            + " (trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,"
            + " preferred_region_codes,raw_answers) values"
            + " (?,array[]::text[],'jeju-si','seogwipo-si',array[]::text[],'{}'::jsonb)",
        OTHER_TRIP);
    assertSqlDenied(
        "authenticated", OWNER, "update public.trip_preferences set trip_plan_id=trip_plan_id");
    assertSqlDenied("authenticated", OWNER, "delete from public.trip_preferences where false");
    assertSqlDenied(
        "authenticated",
        OWNER,
        "insert into public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary)"
            + " values (?,'taxi',1,true)",
        OTHER_TRIP);
    assertSqlDenied(
        "authenticated", OWNER, "update public.trip_transport_modes set trip_plan_id=trip_plan_id");
    assertSqlDenied("authenticated", OWNER, "delete from public.trip_transport_modes where false");
    withRole(
        "service_role",
        null,
        connection -> {
          assertThat(queryCount(connection, "select count(*) from public.trip_preferences"))
              .isOne();
          assertThat(queryCount(connection, "select count(*) from public.trip_transport_modes"))
              .isEqualTo(3);
          assertThat(
                  execute(
                      connection,
                      "update public.trip_preferences set preferred_categories=array['restaurant']"
                          + " where trip_plan_id=?",
                      TRIP))
              .isOne();
          assertThat(
                  execute(
                      connection,
                      "insert into"
                          + " public.trip_preferences(trip_plan_id,preferred_categories,arrival_region_code,departure_region_code,preferred_region_codes,raw_answers)"
                          + " values"
                          + " (?,array[]::text[],'jeju-si','seogwipo-si',array[]::text[],'{}')",
                      OTHER_TRIP))
              .isOne();
          assertThat(
                  execute(
                      connection,
                      "insert into"
                          + " public.trip_transport_modes(trip_plan_id,transport_mode,priority,is_primary)"
                          + " values (?,'taxi',1,true)",
                      OTHER_TRIP))
              .isOne();
          assertThat(
                  execute(
                      connection,
                      "update public.trip_transport_modes set priority=priority where"
                          + " trip_plan_id=?",
                      OTHER_TRIP))
              .isOne();
          assertThat(
                  execute(
                      connection,
                      "delete from public.trip_transport_modes where trip_plan_id=?",
                      OTHER_TRIP))
              .isOne();
          assertThat(
                  execute(
                      connection,
                      "delete from public.trip_preferences where trip_plan_id=?",
                      OTHER_TRIP))
              .isOne();
        });
  }

  @Test
  @Order(3)
  void mode_only_create와replace는commit되고_preference_only는exact_constraint로거부되며_cascade와move는성공한다()
      throws Exception {
    withCommittedTransaction(
        connection ->
            execute(
                connection,
                "insert into public.trip_transport_modes"
                    + " (trip_plan_id,transport_mode,priority,is_primary)"
                    + " values (?,'public_transit',1,true)",
                MODE_ONLY));

    withCommittedTransaction(
        connection -> {
          execute(
              connection,
              "delete from public.trip_transport_modes where trip_plan_id=?",
              MODE_ONLY);
          execute(
              connection,
              "insert into public.trip_transport_modes"
                  + " (trip_plan_id,transport_mode,priority,is_primary) values"
                  + " (?,'rental_car',1,true),(?,'taxi',2,false)",
              MODE_ONLY,
              MODE_ONLY);
        });
    assertThat(modeCount(MODE_ONLY)).isEqualTo(2);

    assertThatThrownBy(
            () ->
                withCommittedTransaction(
                    connection ->
                        execute(
                            connection,
                            "insert into public.trip_preferences"
                                + " (trip_plan_id,preferred_categories,arrival_region_code,"
                                + " departure_region_code,preferred_region_codes,raw_answers)"
                                + " values (?,array['cafe'],'jeju-si','seogwipo-si',"
                                + " array[]::text[],'{}'::jsonb)",
                            PREFERENCE_ONLY)))
        .isInstanceOf(SQLException.class)
        .satisfies(
            failure ->
                assertPostgresConstraint(failure, "23514", "trip_transport_modes_aggregate_check"));
    assertThat(preferenceCount(PREFERENCE_ONLY)).isZero();

    withCommittedTransaction(
        connection -> {
          execute(
              connection,
              "insert into public.trip_transport_modes"
                  + " (trip_plan_id,transport_mode,priority,is_primary)"
                  + " values (?,'taxi',1,true)",
              CASCADE);
          execute(
              connection,
              "insert into public.trip_preferences"
                  + " (trip_plan_id,preferred_categories,arrival_region_code,"
                  + " departure_region_code,preferred_region_codes,raw_answers)"
                  + " values (?,array[]::text[],'jeju-si','seogwipo-si',"
                  + " array[]::text[],'{}'::jsonb)",
              CASCADE);
        });
    withCommittedTransaction(
        connection -> execute(connection, "delete from public.trip_plans where id=?", CASCADE));
    assertThat(modeCount(CASCADE)).isZero();
    assertThat(preferenceCount(CASCADE)).isZero();

    withCommittedTransaction(
        connection ->
            execute(
                connection,
                "update public.trip_transport_modes set trip_plan_id=? where trip_plan_id=?",
                MODE_TARGET,
                MODE_ONLY));
    assertThat(modeCount(MODE_ONLY)).isZero();
    assertThat(modeCount(MODE_TARGET)).isEqualTo(2);
  }

  @Test
  @Order(4)
  void DB_region경계는_여섯_ASCII공백을거부하고_그밖의_C0문자는허용한다() throws Exception {
    assertThatThrownBy(
            () ->
                withCommittedTransaction(
                    connection ->
                        execute(
                            connection,
                            "update public.trip_preferences set departure_region_code="
                                + " E'\\tseogwipo-si\\013' where trip_plan_id=?",
                            TRIP)))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(
            () ->
                withCommittedTransaction(
                    connection ->
                        execute(
                            connection,
                            "update public.trip_preferences set preferred_region_codes="
                                + " array[E'\\taewol\\013'] where trip_plan_id=?",
                            TRIP)))
        .isInstanceOf(SQLException.class);

    withCommittedTransaction(
        connection ->
            execute(
                connection,
                "update public.trip_preferences set departure_region_code="
                    + " E'\\001seogwipo-si\\010', preferred_region_codes="
                    + " array[E'\\001aewol\\010'] where trip_plan_id=?",
                TRIP));
    assertThat(
            jdbc.queryForObject(
                "select departure_region_code from public.trip_preferences where trip_plan_id=?",
                String.class,
                TRIP))
        .isEqualTo("\u0001seogwipo-si\b");
    assertThat(
            jdbc.queryForObject(
                "select preferred_region_codes = array[E'\\001aewol\\010']"
                    + " from public.trip_preferences where trip_plan_id=?",
                Boolean.class,
                TRIP))
        .isTrue();
    jdbc.update(
        "update public.trip_preferences set departure_region_code='seogwipo-si',"
            + " preferred_region_codes=array[]::text[] where trip_plan_id=?",
        TRIP);
  }

  @Test
  @Order(5)
  void deferred_aggregate는_statement를허용하지만_commit에서실패하고_rows를보존한다() throws Exception {
    List<java.util.Map<String, Object>> before = modes();
    AtomicBoolean statementCompleted = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                withCommittedTransaction(
                    connection -> {
                      assertThat(
                              execute(
                                  connection,
                                  "update public.trip_transport_modes set is_primary=false where"
                                      + " trip_plan_id=? and priority=1",
                                  TRIP))
                          .isOne();
                      statementCompleted.set(true);
                    }))
        .isInstanceOf(SQLException.class);

    assertThat(statementCompleted).isTrue();
    assertThat(modes()).isEqualTo(before);
  }

  @Test
  @Order(6)
  void reserved_target는_previous_schema뒤에fresh적용되고_replay해도객체ACLpolicy가같다() throws Exception {
    assertThat(TARGET).isGreaterThan("20260906000001_trip_accommodation_contract.sql");
    int beforeReplayPolicies = policyCount();
    List<java.util.Map<String, Object>> beforeReplayModes = modes();

    PostgreSqlTestContainerFactory.executeScript(container, target());

    assertThat(policyCount()).isEqualTo(beforeReplayPolicies);
    assertThat(modes()).isEqualTo(beforeReplayModes);
  }

  private static Path target() {
    return PostgreSqlTestContainerFactory.locateRepositoryRoot()
        .resolve("supabase/migrations")
        .resolve(TARGET);
  }

  private static int policyCount() {
    return jdbc.queryForObject(
        "select count(*) from pg_policies where schemaname='public' and tablename in"
            + " ('trip_preferences','trip_transport_modes')",
        Integer.class);
  }

  private static List<java.util.Map<String, Object>> modes() {
    return jdbc.queryForList(
        "select transport_mode,priority,is_primary from public.trip_transport_modes where"
            + " trip_plan_id=? order by priority",
        TRIP);
  }

  private static int modeCount(UUID tripId) {
    return jdbc.queryForObject(
        "select count(*) from public.trip_transport_modes where trip_plan_id=?",
        Integer.class,
        tripId);
  }

  private static int preferenceCount(UUID tripId) {
    return jdbc.queryForObject(
        "select count(*) from public.trip_preferences where trip_plan_id=?", Integer.class, tripId);
  }

  private static void insertTrip(UUID tripId, UUID owner) {
    jdbc.update(
        """
        insert into public.trip_plans
          (id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
           source_mode,data_version)
        values (?,?,'issue46-' || ?::text,'Issue 46 relation','draft',date '2026-09-01',
                date '2026-09-03','Asia/Seoul','normal','fixture','issue46-v1')
        """,
        tripId,
        owner,
        tripId);
  }

  private static void assertPostgresConstraint(
      Throwable failure, String sqlState, String constraint) {
    Throwable current = failure;
    while (current != null && !(current instanceof PSQLException)) {
      current = current.getCause();
    }
    assertThat(current).isInstanceOf(PSQLException.class);
    PSQLException postgres = (PSQLException) current;
    assertThat(postgres.getSQLState()).isEqualTo(sqlState);
    assertThat(postgres.getServerErrorMessage()).isNotNull();
    assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
  }

  private static void assertPrivilege(
      String role, String table, String privilege, boolean expected) {
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

  private static void assertSqlDenied(String role, UUID subject, String sql, Object... parameters) {
    assertThatThrownBy(
            () -> withRole(role, subject, connection -> execute(connection, sql, parameters)))
        .isInstanceOf(SQLException.class)
        .satisfies(
            failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
  }

  private static void withRole(String role, UUID subject, SqlWork work) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute("set role " + role);
        if (subject != null) {
          try (PreparedStatement jwt =
              connection.prepareStatement("select set_config('request.jwt.claim.sub',?,true)")) {
            jwt.setString(1, subject.toString());
            jwt.executeQuery();
          }
        }
        work.run(connection);
        connection.rollback();
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      } finally {
        statement.execute("reset role");
      }
    }
  }

  private static void withCommittedTransaction(SqlWork work) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        work.run(connection);
        connection.commit();
      } catch (Exception failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static int queryCount(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    }
  }

  private static int execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      return statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection connection) throws Exception;
  }
}
