package com.timingjeju.api.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.idempotency.IdempotencyAcquisition;
import com.timingjeju.api.application.idempotency.IdempotencyException;
import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyScope;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class JdbcIdempotencyRecordRepositoryIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
  private static final String HASH = "a".repeat(64);
  private static final IdempotencyScope SCOPE =
      new IdempotencyScope(
          UUID.fromString("10000000-0000-0000-0000-000000000017"),
          "POST",
          "/api/v1/trips",
          UUID.fromString("018f6f2a-60a0-7f5b-8c61-8f548f34bc31"));

  @Autowired private JdbcIdempotencyRecordRepository repository;
  @Autowired private IdempotencyUseCase useCase;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from public.api_idempotency_records");
    jdbcTemplate.update("delete from public.user_profiles where email like '%@idempotency.test'");
    jdbcTemplate.update("delete from auth.users where email like '%@idempotency.test'");
  }

  @Test
  void migration은_scope와_상태_TTL_lease_body_size를_DB_constraint로_고정한다() {
    List<String> columns =
        jdbcTemplate.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'api_idempotency_records'
            order by ordinal_position
            """,
            String.class);

    assertThat(columns)
        .contains(
            "owner_sub",
            "http_method",
            "normalized_path",
            "idempotency_key",
            "request_hash",
            "attempt_token",
            "state",
            "response_status",
            "response_headers",
            "response_body",
            "lease_expires_at",
            "expires_at")
        .doesNotContain("authorization", "bearer_token", "request_body", "email");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select relrowsecurity
                from pg_class
                where oid = 'public.api_idempotency_records'::regclass
                """,
                Boolean.class))
        .isTrue();
  }

  @Test
  void 완료_응답의_status_header_body를_byte_equivalent하게_replay한다() {
    IdempotencyResponse response =
        new IdempotencyResponse(
            201,
            List.of(
                new IdempotencyHeader("Content-Type", "application/json;charset=UTF-8"),
                new IdempotencyHeader("Location", "/api/v1/trips/17")),
            "{\"tripId\":\"17\"}".getBytes(StandardCharsets.UTF_8));

    IdempotencyAcquisition acquired = repository.acquire(SCOPE, HASH, NOW);
    assertThat(acquired.disposition()).isEqualTo(IdempotencyAcquisition.Disposition.ACQUIRED);
    repository.complete(
        SCOPE, HASH, acquired.attemptToken().orElseThrow(), response, NOW.plusSeconds(1));
    IdempotencyResponse replay =
        repository.acquire(SCOPE, HASH, NOW.plusSeconds(2)).response().orElseThrow();

    assertThat(replay.status()).isEqualTo(response.status());
    assertThat(replay.headers()).containsExactlyElementsOf(response.headers());
    assertThat(replay.body()).containsExactly(response.body());
  }

  @Test
  void 동일_scope의_다른_hash와_처리중_same_hash를_구분한다() {
    repository.acquire(SCOPE, HASH, NOW);

    assertThat(repository.acquire(SCOPE, "b".repeat(64), NOW.plusSeconds(1)).disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.REUSED);
    assertThat(repository.acquire(SCOPE, HASH, NOW.plusSeconds(1)).disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.PROCESSING);
  }

  @Test
  void processing_lease_2분과_completed_TTL_24시간_경계에서만_takeover한다() {
    repository.acquire(SCOPE, HASH, NOW);
    assertThat(
            repository
                .acquire(SCOPE, HASH, NOW.plus(Duration.ofMinutes(2)).minusNanos(1_000))
                .disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.PROCESSING);
    IdempotencyAcquisition leaseWinner =
        repository.acquire(SCOPE, HASH, NOW.plus(Duration.ofMinutes(2)));
    assertThat(leaseWinner.disposition()).isEqualTo(IdempotencyAcquisition.Disposition.ACQUIRED);
    repository.complete(
        SCOPE,
        HASH,
        leaseWinner.attemptToken().orElseThrow(),
        response("old"),
        NOW.plus(Duration.ofMinutes(2)));
    assertThat(
            repository
                .acquire(
                    SCOPE,
                    HASH,
                    NOW.plus(Duration.ofMinutes(2)).plus(Duration.ofHours(24)).minusNanos(1_000))
                .disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.REPLAY);
    assertThat(
            repository
                .acquire(SCOPE, HASH, NOW.plus(Duration.ofMinutes(2)).plus(Duration.ofHours(24)))
                .disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.ACQUIRED);
  }

  @Test
  void DB도_5xx_completed를_거부한다() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into public.api_idempotency_records (
                      owner_sub, http_method, normalized_path, idempotency_key, request_hash, attempt_token,
                      state, response_status, response_headers, response_body,
                      created_at, completed_at, expires_at
                    ) values (?, 'POST', '/api/v1/trips', ?, ?, ?, 'COMPLETED', 503, ?, ?, ?, ?, ?)
                    """,
                    SCOPE.ownerSub(),
                    SCOPE.idempotencyKey(),
                    HASH,
                    UUID.randomUUID(),
                    new byte[] {0, 0, 0, 0},
                    new byte[0],
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofHours(24))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void lease가_만료된_이전_attempt는_새_attempt를_완료하거나_release할_수_없다() {
    IdempotencyAcquisition stale = repository.acquire(SCOPE, HASH, NOW);
    IdempotencyAcquisition current =
        repository.acquire(SCOPE, HASH, NOW.plus(Duration.ofMinutes(2)));

    assertThatThrownBy(
            () ->
                repository.complete(
                    SCOPE,
                    HASH,
                    stale.attemptToken().orElseThrow(),
                    response("stale"),
                    NOW.plus(Duration.ofMinutes(2))))
        .isInstanceOf(IllegalStateException.class);
    repository.release(SCOPE, HASH, stale.attemptToken().orElseThrow());
    assertThat(repository.acquire(SCOPE, HASH, NOW.plusSeconds(121)).disposition())
        .isEqualTo(IdempotencyAcquisition.Disposition.PROCESSING);
    repository.complete(
        SCOPE,
        HASH,
        current.attemptToken().orElseThrow(),
        response("current"),
        NOW.plusSeconds(121));
  }

  @Test
  void DB도_1MiB_초과_응답을_거부한다() {
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into public.api_idempotency_records (
                      owner_sub, http_method, normalized_path, idempotency_key, request_hash, attempt_token,
                      state, response_status, response_headers, response_body,
                      created_at, completed_at, expires_at
                    ) values (?, 'POST', '/api/v1/trips', ?, ?, ?, 'COMPLETED', 201, ?, ?, ?, ?, ?)
                    """,
                    SCOPE.ownerSub(),
                    SCOPE.idempotencyKey(),
                    HASH,
                    UUID.randomUUID(),
                    new byte[] {0, 0, 0, 0},
                    new byte[1_048_577],
                    NOW,
                    NOW,
                    NOW.plus(Duration.ofHours(24))))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void 실제_트랜잭션에서_동시_loser는_retry_after_1을_받고_업무는_한번만_실행된다() throws Exception {
    IdempotencyRequest request = request(UUID.randomUUID(), UUID.randomUUID());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger executions = new AtomicInteger();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var winner =
          executor.submit(
              () ->
                  useCase.execute(
                      request,
                      () -> {
                        executions.incrementAndGet();
                        started.countDown();
                        await(finish);
                        return response("winner");
                      }));
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> useCase.execute(request, () -> response("loser")))
          .isInstanceOf(IdempotencyException.class)
          .satisfies(
              error -> assertThat(((IdempotencyException) error).retryAfterSeconds()).hasValue(1));
      finish.countDown();
      assertThat(winner.get(2, TimeUnit.SECONDS).body()).containsExactly(bytes("winner"));
    }
    assertThat(executions).hasValue(1);
  }

  @Test
  void operation_예외는_업무_row와_processing_marker를_함께_rollback하고_재시도된다() {
    UUID owner = UUID.randomUUID();
    IdempotencyRequest request = request(owner, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                useCase.execute(
                    request,
                    () -> {
                      insertUser(owner);
                      throw new IllegalStateException("unexpected");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(userCount(owner)).isZero();
    assertThat(recordCount(request.scope())).isZero();
    assertThat(useCase.execute(request, () -> response("retry")).body())
        .containsExactly(bytes("retry"));
  }

  @Test
  void 반환된_5xx도_업무_row와_marker를_남기지_않는다() {
    UUID owner = UUID.randomUUID();
    IdempotencyRequest request = request(owner, UUID.randomUUID());

    IdempotencyResponse failure =
        useCase.execute(
            request,
            () -> {
              insertUser(owner);
              return new IdempotencyResponse(503, List.of(), bytes("temporary"));
            });

    assertThat(failure.status()).isEqualTo(503);
    assertThat(userCount(owner)).isZero();
    assertThat(recordCount(request.scope())).isZero();
  }

  private void insertUser(UUID owner) {
    String email = owner + "@idempotency.test";
    jdbcTemplate.update("insert into auth.users (id, email) values (?, ?)", owner, email);
    jdbcTemplate.update("insert into public.user_profiles (id, email) values (?, ?)", owner, email);
  }

  private int userCount(UUID owner) {
    return jdbcTemplate.queryForObject(
        "select count(*) from public.user_profiles where id = ?", Integer.class, owner);
  }

  private int recordCount(IdempotencyScope scope) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) from public.api_idempotency_records
        where owner_sub = ? and http_method = ? and normalized_path = ? and idempotency_key = ?
        """,
        Integer.class,
        scope.ownerSub(),
        scope.method(),
        scope.normalizedPath(),
        scope.idempotencyKey());
  }

  private static IdempotencyRequest request(UUID owner, UUID key) {
    return IdempotencyRequest.create(owner, "POST", "/api/v1/trips", key.toString(), bytes("{}"));
  }

  private static IdempotencyResponse response(String body) {
    return new IdempotencyResponse(201, List.of(), bytes(body));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timeout");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
