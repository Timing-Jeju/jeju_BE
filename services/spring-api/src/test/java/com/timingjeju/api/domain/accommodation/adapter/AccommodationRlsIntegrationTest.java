package com.timingjeju.api.domain.accommodation.adapter;

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
class AccommodationRlsIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000971");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000972");
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000973");
  private static final List<String> TABLES =
      List.of("trip_accommodations", "accommodation_idempotency");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUpAggregate() {
    cleanFixtures();
    jdbc.update(
        "insert into auth.users (id,email) values (?,?)", OWNER, "accommodation-rls@issue68.test");
    jdbc.update(
        "insert into public.user_profiles (id,email) values (?,?)",
        OWNER,
        "accommodation-rls@issue68.test");
    jdbc.update(
        """
        insert into public.trip_plans(
          id,user_id,public_token,title,status,start_date,end_date,timezone,user_pace,
          source_mode,data_version
        ) values (?,?,'issue68-rls-trip','ACL 숙소','draft','2026-09-01','2026-09-03',
          'Asia/Seoul','normal','fixture','issue68-rls-v1')
        """,
        TRIP,
        OWNER);
    jdbc.update(
        """
        insert into public.trip_accommodations(
          id,trip_plan_id,custom_name,check_in_date,check_out_date,check_in_time,
          check_out_time,sequence_no,source
        ) values (?,?,'원본 숙소','2026-09-01','2026-09-03','15:00','11:00',1,'user_input')
        """,
        ACCOMMODATION,
        TRIP);
    jdbc.update(
        """
        with stamp as (select now() as value)
        insert into public.accommodation_idempotency(
          owner_sub,trip_plan_id,idempotency_key,request_hash,accommodation_id,created_at,expires_at
        ) select ?,?,'rls-original',repeat('a',64),?,value,value+interval '24 hours' from stamp
        """,
        OWNER,
        TRIP,
        ACCOMMODATION);
  }

  @AfterEach
  void cleanFixtures() {
    jdbc.update("delete from public.trip_plans where id=?", TRIP);
    jdbc.update("delete from public.user_profiles where id=?", OWNER);
    jdbc.update("delete from auth.users where id=?", OWNER);
  }

  @Test
  void accommodation_tables는_client_DML을_ACL에서_거부하고_aggregate를_보존한다() throws Exception {
    assertRlsAndPrivileges();
    String before = fingerprint();

    for (String role : List.of("anon", "authenticated")) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          execute(connection, "set role " + role);
          assertDenied(
              connection,
              "insert into public.trip_accommodations(id,trip_plan_id,custom_name,check_in_date,check_out_date,check_in_time,check_out_time,sequence_no) values (gen_random_uuid(),?,'위조','2026-09-01','2026-09-02','15:00','11:00',2)",
              "trip_accommodations",
              TRIP);
          assertDenied(
              connection,
              "update public.trip_accommodations set custom_name='변조' where id=?",
              "trip_accommodations",
              ACCOMMODATION);
          assertDenied(
              connection,
              "delete from public.trip_accommodations where id=?",
              "trip_accommodations",
              ACCOMMODATION);
          assertDenied(
              connection,
              "insert into public.accommodation_idempotency(owner_sub,trip_plan_id,idempotency_key,request_hash,accommodation_id,expires_at) values (?,?, 'rls-forged',repeat('b',64),gen_random_uuid(),now()+interval '24 hours')",
              "accommodation_idempotency",
              OWNER,
              TRIP);
          assertDenied(
              connection,
              "update public.accommodation_idempotency set request_hash=repeat('b',64) where owner_sub=? and trip_plan_id=? and idempotency_key='rls-original'",
              "accommodation_idempotency",
              OWNER,
              TRIP);
          assertDenied(
              connection,
              "delete from public.accommodation_idempotency where owner_sub=? and trip_plan_id=? and idempotency_key='rls-original'",
              "accommodation_idempotency",
              OWNER,
              TRIP);
        } finally {
          connection.rollback();
          execute(connection, "reset role");
        }
      }
      assertThat(fingerprint()).isEqualTo(before);
    }
  }

  private void assertRlsAndPrivileges() {
    for (String table : TABLES) {
      assertThat(
              jdbc.queryForObject(
                  "select relrowsecurity from pg_class where oid=('public.' || ?)::regclass",
                  Boolean.class,
                  table))
          .as("RLS on %s", table)
          .isTrue();
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertPrivilege("anon", table, privilege, false);
        assertPrivilege("authenticated", table, privilege, false);
        assertPrivilege("service_role", table, privilege, true);
      }
      for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
        assertPrivilege("service_role", table, privilege, false);
      }
    }
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from pg_policies
                where schemaname='public'
                  and tablename in ('trip_accommodations','accommodation_idempotency')
                  and cmd in ('INSERT','UPDATE','DELETE')
                """,
                Integer.class))
        .isZero();
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

  private String fingerprint() {
    return jdbc.queryForObject(
        """
        select p.xmin::text || ':' || p.revision::text || ':' || p.status || ':' ||
          coalesce(p.active_schedule_version_id::text,'null') || ':' ||
          coalesce(p.total_score::text,'null') || ':' || a.xmin::text || ':' || a.custom_name || ':' ||
          marker.xmin::text || ':' || marker.request_hash
        from public.trip_plans p
        join public.trip_accommodations a on a.trip_plan_id=p.id and a.id=?
        join public.accommodation_idempotency marker
          on marker.trip_plan_id=p.id and marker.idempotency_key='rls-original'
        where p.id=?
        """,
        String.class,
        ACCOMMODATION,
        TRIP);
  }

  private static void assertDenied(
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

  private static void execute(Connection connection, String sql, Object... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.execute();
    }
  }
}
