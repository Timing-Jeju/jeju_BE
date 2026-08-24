package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class ScheduleRevisionRunSchemaIntegrationTest {

  private static final UUID OWNER_ONE = UUID.fromString("17000000-0000-0000-0000-000000000001");
  private static final UUID OWNER_TWO = UUID.fromString("17000000-0000-0000-0000-000000000002");
  private static final UUID TRIP_ONE = UUID.fromString("17010000-0000-0000-0000-000000000001");
  private static final UUID TRIP_TWO = UUID.fromString("17010000-0000-0000-0000-000000000002");
  private static final UUID DAY_ONE = UUID.fromString("17020000-0000-0000-0000-000000000001");
  private static final UUID DAY_TWO = UUID.fromString("17020000-0000-0000-0000-000000000002");
  private static final UUID BASE_ONE = UUID.fromString("17030000-0000-0000-0000-000000000001");
  private static final UUID BASE_TWO = UUID.fromString("17030000-0000-0000-0000-000000000002");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUpCanonicalLineage() {
    cleanUpRows();
    for (UUID owner : List.of(OWNER_ONE, OWNER_TWO)) {
      jdbcTemplate.update(
          "insert into auth.users (id, email) values (?, ?)", owner, owner + "@revision-run.test");
      jdbcTemplate.update(
          "insert into public.user_profiles (id, email) values (?, ?)",
          owner,
          owner + "@revision-run.test");
    }
    insertTripLineage(TRIP_ONE, OWNER_ONE, DAY_ONE, BASE_ONE, "revision-run-trip-one");
    insertTripLineage(TRIP_TWO, OWNER_TWO, DAY_TWO, BASE_TWO, "revision-run-trip-two");
  }

  @AfterEach
  void cleanUpRows() {
    jdbcTemplate.update("delete from public.trip_plans where id in (?, ?)", TRIP_ONE, TRIP_TWO);
    jdbcTemplate.update(
        "delete from public.user_profiles where id in (?, ?)", OWNER_ONE, OWNER_TWO);
    jdbcTemplate.update("delete from auth.users where id in (?, ?)", OWNER_ONE, OWNER_TWO);
  }

  @Test
  void fresh_schema_has_exact_columns_composite_foreign_keys_and_closed_status() {
    List<String> columns =
        jdbcTemplate.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'schedule_revision_runs'
            order by ordinal_position
            """,
            String.class);

    assertThat(columns)
        .contains(
            "id",
            "owner_user_id",
            "trip_plan_id",
            "base_schedule_version_id",
            "target_trip_day_id",
            "status",
            "contract_version",
            "algorithm_version",
            "idempotency_key",
            "request_hash",
            "attempt_count",
            "fencing_token",
            "lease_owner",
            "lease_expires_at",
            "heartbeat_at",
            "next_attempt_at")
        .doesNotContain("structured_input", "raw_request", "mcp_compute_call_log_id");

    Integer compositeForeignKeys =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from pg_constraint
            where conrelid = 'public.schedule_revision_runs'::regclass
              and contype = 'f'
              and array_length(conkey, 1) = 2
            """,
            Integer.class);
    assertThat(compositeForeignKeys).isEqualTo(3);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    insertSql(),
                    UUID.randomUUID(),
                    OWNER_ONE,
                    TRIP_ONE,
                    BASE_ONE,
                    DAY_ONE,
                    "unknown",
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void existing_but_mismatched_owner_base_and_day_lineage_is_rejected() {
    assertThatThrownBy(
            () ->
                insertQueued(
                    UUID.randomUUID(), OWNER_TWO, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                insertQueued(
                    UUID.randomUUID(), OWNER_ONE, TRIP_ONE, BASE_TWO, DAY_ONE, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                insertQueued(
                    UUID.randomUUID(), OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_TWO, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void two_session_same_idempotency_scope_creates_exactly_one_canonical_row() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    CyclicBarrier start = new CyclicBarrier(2);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          executor.submit(() -> concurrentInsert(UUID.randomUUID(), idempotencyKey, start));
      Future<Integer> second =
          executor.submit(() -> concurrentInsert(UUID.randomUUID(), idempotencyKey, start));

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
    }

    Integer canonicalRows =
        jdbcTemplate.queryForObject(
            """
            select count(*) from public.schedule_revision_runs
            where owner_user_id = ? and trip_plan_id = ? and idempotency_key = ?
            """,
            Integer.class,
            OWNER_ONE,
            TRIP_ONE,
            idempotencyKey);
    assertThat(canonicalRows).isEqualTo(1);

    jdbcTemplate.update(
        "delete from public.schedule_revision_runs where trip_plan_id = ?", TRIP_ONE);
    UUID fencingRun = UUID.randomUUID();
    insertQueued(fencingRun, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());
    CyclicBarrier claimStart = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          executor.submit(() -> concurrentClaim(fencingRun, "worker-a", claimStart));
      Future<Integer> second =
          executor.submit(() -> concurrentClaim(fencingRun, "worker-b", claimStart));

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
    }
    assertThat(
            jdbcTemplate.queryForMap(
                "select status, attempt_count, fencing_token from public.schedule_revision_runs where id = ?",
                fencingRun))
        .containsEntry("status", "running")
        .containsEntry("attempt_count", 1)
        .containsEntry("fencing_token", 1L);
  }

  @Test
  void failure_code_boundaries_and_every_fencing_transition_are_closed() throws Exception {
    UUID runId = UUID.randomUUID();
    insertQueued(runId, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update public.schedule_revision_runs set contract_version = 'v2' where id = ?",
                    runId))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertRejected(
        "update public.schedule_revision_runs set attempt_count = 1 where id = ?", runId);
    assertRejected(
        """
        update public.schedule_revision_runs
        set status = 'running', attempt_count = 1, fencing_token = 2,
            lease_owner = 'worker-1', heartbeat_at = statement_timestamp(),
            lease_expires_at = statement_timestamp() + interval '30 seconds',
            next_attempt_at = null, started_at = statement_timestamp()
        where id = ?
        """,
        runId);

    claim(runId, "worker-1");
    assertRejected(
        """
        update public.schedule_revision_runs
        set lease_owner = 'worker-stolen', heartbeat_at = statement_timestamp(),
            lease_expires_at = statement_timestamp() + interval '30 seconds'
        where id = ?
        """,
        runId);
    assertRejected(
        "update public.schedule_revision_runs set attempt_count = 2 where id = ?", runId);
    jdbcTemplate.update(
        """
        update public.schedule_revision_runs
        set heartbeat_at = statement_timestamp(),
            lease_expires_at = statement_timestamp() + interval '30 seconds'
        where id = ?
        """,
        runId);

    assertRejected(retrySql("null"), runId);
    assertRejected(retrySql("''"), runId);
    assertRejected(retrySql("repeat('x', 101)"), runId);
    jdbcTemplate.update(retrySql("repeat('x', 100)"), runId);
    assertThat(failureCode(runId)).hasSize(100);
    assertRejected(
        "update public.schedule_revision_runs set failure_code = repeat('y', 65) where id = ?",
        runId);

    claim(runId, "worker-2");
    assertThat(failureCode(runId)).isNull();
    assertRejected(terminalSql("failed", "null"), runId);
    assertRejected(terminalSql("failed", "''"), runId);
    jdbcTemplate.update(terminalSql("failed", "repeat('f', 64)"), runId);
    assertThat(failureCode(runId)).hasSize(64);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    update public.schedule_revision_runs
                    set status = 'running', attempt_count = 1, fencing_token = 1,
                        lease_owner = 'worker-1', heartbeat_at = now(),
                        lease_expires_at = now() + interval '30 seconds', started_at = now()
                    where id = ?
                    """,
                    runId))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID cancelledRun = UUID.randomUUID();
    insertQueued(cancelledRun, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());
    claim(cancelledRun, "worker-cancel");
    assertRejected(terminalSql("cancelled", "null"), cancelledRun);
    jdbcTemplate.update(terminalSql("cancelled", "repeat('c', 65)"), cancelledRun);
    assertThat(failureCode(cancelledRun)).hasSize(65);

    UUID maxFailureRun = UUID.randomUUID();
    insertQueued(maxFailureRun, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());
    claim(maxFailureRun, "worker-fail-max");
    jdbcTemplate.update(terminalSql("failed", "repeat('m', 100)"), maxFailureRun);
    assertThat(failureCode(maxFailureRun)).hasSize(100);

    UUID preStartFailedRun = UUID.randomUUID();
    insertQueued(preStartFailedRun, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());
    jdbcTemplate.update(terminalSql("failed", "'PRE_START_FAILED'"), preStartFailedRun);
    assertThat(
            jdbcTemplate.queryForObject(
                "select started_at from public.schedule_revision_runs where id = ?",
                java.time.OffsetDateTime.class,
                preStartFailedRun))
        .isNull();

    UUID exhaustedRun = UUID.randomUUID();
    insertQueued(exhaustedRun, OWNER_ONE, TRIP_ONE, BASE_ONE, DAY_ONE, UUID.randomUUID());
    for (int attempt = 1; attempt <= 4; attempt++) {
      claim(exhaustedRun, "worker-retry-" + attempt);
      jdbcTemplate.update(retrySql("'TEMPORARY_FAILURE'"), exhaustedRun);
    }
    claimWithLease(exhaustedRun, "worker-fifth", 1);
    Thread.sleep(20);

    assertRejected(retrySql("'ATTEMPT_FIVE_RETRY'"), exhaustedRun);
    assertRejected(
        """
        update public.schedule_revision_runs
        set heartbeat_at = statement_timestamp(),
            lease_expires_at = statement_timestamp() + interval '30 seconds'
        where id = ?
        """,
        exhaustedRun);
    assertRejected(terminalSql("succeeded", "null"), exhaustedRun);
    assertRejected(terminalSql("failed", "'GENERAL_FAILURE'"), exhaustedRun);

    CyclicBarrier exhaustedStart = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          executor.submit(() -> concurrentExhaust(exhaustedRun, exhaustedStart));
      Future<Integer> second =
          executor.submit(() -> concurrentExhaust(exhaustedRun, exhaustedStart));
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
    }
    assertThat(
            jdbcTemplate.queryForMap(
                "select status, attempt_count, fencing_token, failure_code from public.schedule_revision_runs where id = ?",
                exhaustedRun))
        .containsEntry("status", "failed")
        .containsEntry("attempt_count", 5)
        .containsEntry("fencing_token", 5L)
        .containsEntry("failure_code", "ASYNC_RUN_RETRY_EXHAUSTED");
  }

  @Test
  void rls_has_no_client_policy_and_only_service_role_has_server_dml() {
    Boolean rlsEnabled =
        jdbcTemplate.queryForObject(
            "select relrowsecurity from pg_class where oid = 'public.schedule_revision_runs'::regclass",
            Boolean.class);
    Integer policies =
        jdbcTemplate.queryForObject(
            """
            select count(*) from pg_policies
            where schemaname = 'public' and tablename = 'schedule_revision_runs'
            """,
            Integer.class);

    assertThat(rlsEnabled).isTrue();
    assertThat(policies).isZero();
    for (String clientRole : List.of("anon", "authenticated")) {
      assertThat(
              jdbcTemplate.queryForObject(
                  "select has_table_privilege(?, 'public.schedule_revision_runs', 'INSERT')",
                  Boolean.class,
                  clientRole))
          .isFalse();
    }
    for (String serverPrivilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
      assertThat(
              jdbcTemplate.queryForObject(
                  "select has_table_privilege('service_role', 'public.schedule_revision_runs', ?)",
                  Boolean.class,
                  serverPrivilege))
          .isTrue();
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "select has_table_privilege('service_role', 'public.schedule_revision_runs', 'TRUNCATE')",
                Boolean.class))
        .isFalse();
  }

  private void insertTripLineage(
      UUID tripId, UUID ownerId, UUID dayId, UUID baseId, String publicToken) {
    jdbcTemplate.update(
        """
        insert into public.trip_plans
          (id, user_id, public_token, start_date, end_date, source_mode, data_version)
        values (?, ?, ?, current_date, current_date, 'fixture', 'revision-run-test-v1')
        """,
        tripId,
        ownerId,
        publicToken);
    jdbcTemplate.update(
        """
        insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
        values (?, ?, 1, current_date)
        """,
        dayId,
        tripId);
    jdbcTemplate.update(
        """
        insert into public.trip_schedule_versions
          (id, trip_plan_id, version_no, status, source_type, created_by_user_id)
        values (?, ?, 1, 'draft', 'initial', ?)
        """,
        baseId,
        tripId,
        ownerId);
  }

  private void insertQueued(
      UUID runId, UUID ownerId, UUID tripId, UUID baseId, UUID dayId, UUID idempotencyKey) {
    jdbcTemplate.update(
        insertSql(), runId, ownerId, tripId, baseId, dayId, "queued", idempotencyKey);
  }

  private int concurrentInsert(UUID runId, UUID idempotencyKey, CyclicBarrier start)
      throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(insertSql() + " on conflict do nothing")) {
      connection.setAutoCommit(false);
      bindInsert(statement, runId, idempotencyKey);
      start.await();
      int inserted = statement.executeUpdate();
      connection.commit();
      return inserted;
    }
  }

  private int concurrentClaim(UUID runId, String workerId, CyclicBarrier start) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                update public.schedule_revision_runs
                set status = 'running', attempt_count = attempt_count + 1,
                    fencing_token = fencing_token + 1, lease_owner = ?,
                    heartbeat_at = statement_timestamp(),
                    lease_expires_at = statement_timestamp() + interval '30 seconds',
                    next_attempt_at = null, started_at = statement_timestamp(),
                    failure_code = null
                where id = ? and status = 'queued'
                """)) {
      connection.setAutoCommit(false);
      statement.setString(1, workerId);
      statement.setObject(2, runId);
      start.await();
      int claimed = statement.executeUpdate();
      connection.commit();
      return claimed;
    }
  }

  private int concurrentExhaust(UUID runId, CyclicBarrier start) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                update public.schedule_revision_runs
                set status = 'failed', completed_at = statement_timestamp(),
                    lease_owner = null, lease_expires_at = null, heartbeat_at = null,
                    next_attempt_at = null, failure_code = 'ASYNC_RUN_RETRY_EXHAUSTED'
                where id = ? and status = 'running'
                """)) {
      connection.setAutoCommit(false);
      statement.setObject(1, runId);
      start.await();
      int recovered = statement.executeUpdate();
      connection.commit();
      return recovered;
    }
  }

  private void claim(UUID runId, String workerId) {
    claimWithLease(runId, workerId, 30_000);
  }

  private void claimWithLease(UUID runId, String workerId, long leaseMillis) {
    jdbcTemplate.update(
        """
        update public.schedule_revision_runs
        set status = 'running', attempt_count = attempt_count + 1,
            fencing_token = fencing_token + 1, lease_owner = ?,
            heartbeat_at = statement_timestamp(),
            lease_expires_at = statement_timestamp() + (? * interval '1 millisecond'),
            next_attempt_at = null, started_at = statement_timestamp(),
            failure_code = null
        where id = ?
        """,
        workerId,
        leaseMillis,
        runId);
  }

  private void assertRejected(String sql, UUID runId) {
    assertThatThrownBy(() -> jdbcTemplate.update(sql, runId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private String retrySql(String failureExpression) {
    return """
        update public.schedule_revision_runs
        set status = 'queued', started_at = null, completed_at = null,
            lease_owner = null, lease_expires_at = null, heartbeat_at = null,
            next_attempt_at = statement_timestamp() + interval '1 second',
            failure_code = %s
        where id = ?
        """
        .formatted(failureExpression);
  }

  private String terminalSql(String status, String failureExpression) {
    return """
        update public.schedule_revision_runs
        set status = '%s', completed_at = statement_timestamp(),
            lease_owner = null, lease_expires_at = null, heartbeat_at = null,
            next_attempt_at = null, failure_code = %s
        where id = ?
        """
        .formatted(status, failureExpression);
  }

  private String failureCode(UUID runId) {
    return jdbcTemplate.queryForObject(
        "select failure_code from public.schedule_revision_runs where id = ?", String.class, runId);
  }

  private void bindInsert(PreparedStatement statement, UUID runId, UUID idempotencyKey)
      throws Exception {
    statement.setObject(1, runId);
    statement.setObject(2, OWNER_ONE);
    statement.setObject(3, TRIP_ONE);
    statement.setObject(4, BASE_ONE);
    statement.setObject(5, DAY_ONE);
    statement.setString(6, "queued");
    statement.setObject(7, idempotencyKey);
  }

  private String insertSql() {
    return """
        insert into public.schedule_revision_runs
          (id, owner_user_id, trip_plan_id, base_schedule_version_id,
           target_trip_day_id, status, contract_version, algorithm_version,
           idempotency_key, request_hash, next_attempt_at)
        values (?, ?, ?, ?, ?, ?, 'revision-v1', 'algorithm-v1', ?,
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', now())
        """;
  }
}
