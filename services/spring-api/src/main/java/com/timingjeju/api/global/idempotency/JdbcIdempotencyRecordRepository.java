package com.timingjeju.api.global.idempotency;

import com.timingjeju.api.application.idempotency.IdempotencyAcquisition;
import com.timingjeju.api.application.idempotency.IdempotencyAttemptTokenGenerator;
import com.timingjeju.api.application.idempotency.IdempotencyRecordStore;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyRecordRepository implements IdempotencyRecordStore {

  private static final Duration PROCESSING_LEASE = Duration.ofMinutes(2);
  private static final Duration COMPLETED_TTL = Duration.ofHours(24);
  private static final int MAX_ACQUIRE_ATTEMPTS = 2;

  private final JdbcTemplate jdbcTemplate;
  private final IdempotencyAttemptTokenGenerator attemptTokenGenerator;
  private final IdempotencyHeaderCodec headerCodec = new IdempotencyHeaderCodec();

  public JdbcIdempotencyRecordRepository(
      JdbcTemplate jdbcTemplate, IdempotencyAttemptTokenGenerator attemptTokenGenerator) {
    this.jdbcTemplate = jdbcTemplate;
    this.attemptTokenGenerator = attemptTokenGenerator;
  }

  @Override
  public IdempotencyAcquisition acquire(IdempotencyScope scope, String requestHash, Instant now) {
    for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
      Optional<IdempotencyAcquisition> acquisition = tryAcquire(scope, requestHash, now);
      if (acquisition.isPresent()) {
        return acquisition.orElseThrow();
      }
    }

    // A winner may release between each conditional UPDATE and SELECT. Avoid turning that
    // bounded, transient race into a 5xx; the caller uses the normal PROCESSING retry contract.
    return IdempotencyAcquisition.processing();
  }

  private Optional<IdempotencyAcquisition> tryAcquire(
      IdempotencyScope scope, String requestHash, Instant now) {
    UUID attemptToken = attemptTokenGenerator.generate();
    int inserted =
        jdbcTemplate.update(
            """
            insert into public.api_idempotency_records (
              owner_sub, http_method, normalized_path, idempotency_key,
              request_hash, attempt_token, state, created_at, lease_expires_at, expires_at
            ) values (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?)
            on conflict (owner_sub, http_method, normalized_path, idempotency_key) do nothing
            """,
            scope.ownerSub(),
            scope.method(),
            scope.normalizedPath(),
            scope.idempotencyKey(),
            requestHash,
            attemptToken,
            timestamp(now),
            timestamp(now.plus(PROCESSING_LEASE)),
            timestamp(now.plus(COMPLETED_TTL)));
    if (inserted == 1) {
      return Optional.of(IdempotencyAcquisition.acquired(attemptToken));
    }

    int takenOver =
        jdbcTemplate.update(
            """
            update public.api_idempotency_records
            set request_hash = ?, attempt_token = ?, state = 'PROCESSING',
                response_status = null, response_headers = null, response_body = null,
                created_at = ?, lease_expires_at = ?, completed_at = null, expires_at = ?
            where owner_sub = ? and http_method = ? and normalized_path = ? and idempotency_key = ?
              and (
                expires_at <= ?
                or (state = 'PROCESSING' and lease_expires_at <= ?)
              )
            """,
            requestHash,
            attemptToken,
            timestamp(now),
            timestamp(now.plus(PROCESSING_LEASE)),
            timestamp(now.plus(COMPLETED_TTL)),
            scope.ownerSub(),
            scope.method(),
            scope.normalizedPath(),
            scope.idempotencyKey(),
            timestamp(now),
            timestamp(now));
    if (takenOver == 1) {
      return Optional.of(IdempotencyAcquisition.acquired(attemptToken));
    }

    return findCurrent(scope, requestHash);
  }

  @Override
  public void complete(
      IdempotencyScope scope,
      String requestHash,
      UUID attemptToken,
      IdempotencyResponse response,
      Instant now) {
    if (response.isUnexpectedServerError()) {
      throw new IllegalArgumentException("5xx response는 idempotency 완료 응답으로 저장할 수 없습니다.");
    }
    int updated =
        jdbcTemplate.update(
            """
            update public.api_idempotency_records
            set state = 'COMPLETED', response_status = ?, response_headers = ?, response_body = ?,
                lease_expires_at = null, completed_at = ?, expires_at = ?
            where owner_sub = ? and http_method = ? and normalized_path = ? and idempotency_key = ?
              and request_hash = ? and attempt_token = ? and state = 'PROCESSING'
            """,
            response.status(),
            headerCodec.encode(response.headers()),
            response.body(),
            timestamp(now),
            timestamp(now.plus(COMPLETED_TTL)),
            scope.ownerSub(),
            scope.method(),
            scope.normalizedPath(),
            scope.idempotencyKey(),
            requestHash,
            attemptToken);
    if (updated != 1) {
      throw new IllegalStateException("획득한 idempotency marker만 완료할 수 있습니다.");
    }
  }

  @Override
  public void release(IdempotencyScope scope, String requestHash, UUID attemptToken) {
    jdbcTemplate.update(
        """
        delete from public.api_idempotency_records
        where owner_sub = ? and http_method = ? and normalized_path = ? and idempotency_key = ?
          and request_hash = ? and attempt_token = ? and state = 'PROCESSING'
        """,
        scope.ownerSub(),
        scope.method(),
        scope.normalizedPath(),
        scope.idempotencyKey(),
        requestHash,
        attemptToken);
  }

  private Optional<IdempotencyAcquisition> findCurrent(IdempotencyScope scope, String requestHash) {
    List<StoredRecord> records =
        jdbcTemplate.query(
            """
            select request_hash, state, response_status, response_headers, response_body
            from public.api_idempotency_records
            where owner_sub = ? and http_method = ? and normalized_path = ? and idempotency_key = ?
            """,
            (resultSet, rowNumber) -> storedRecord(resultSet),
            scope.ownerSub(),
            scope.method(),
            scope.normalizedPath(),
            scope.idempotencyKey());
    if (records.isEmpty()) {
      return Optional.empty();
    }
    if (records.size() != 1) {
      throw new IllegalStateException("idempotency acquisition 결과가 유일하지 않습니다.");
    }
    StoredRecord record = records.getFirst();
    if (!record.requestHash.equals(requestHash)) {
      return Optional.of(IdempotencyAcquisition.reused());
    }
    if (record.response == null) {
      return Optional.of(IdempotencyAcquisition.processing());
    }
    return Optional.of(IdempotencyAcquisition.replay(record.response));
  }

  private StoredRecord storedRecord(ResultSet resultSet) throws SQLException {
    String state = resultSet.getString("state");
    if ("PROCESSING".equals(state)) {
      return new StoredRecord(resultSet.getString("request_hash"), null);
    }
    if (!"COMPLETED".equals(state)) {
      throw new IllegalStateException("지원하지 않는 idempotency state입니다.");
    }
    return new StoredRecord(
        resultSet.getString("request_hash"),
        new IdempotencyResponse(
            resultSet.getInt("response_status"),
            headerCodec.decode(resultSet.getBytes("response_headers")),
            resultSet.getBytes("response_body")));
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private record StoredRecord(String requestHash, IdempotencyResponse response) {}
}
