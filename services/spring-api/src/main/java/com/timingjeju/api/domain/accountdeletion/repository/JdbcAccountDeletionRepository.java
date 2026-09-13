package com.timingjeju.api.domain.accountdeletion.repository;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountDeletionRepository implements AccountDeletionRepository {
  private static final String COLUMNS =
      """
      id, user_profile_id, idempotency_hash, request_hash, status_token_hash,
      status_token_ciphertext, status_token_key_version, status_token_expires_at,
      auth_subject_ciphertext, auth_subject_key_version, status, current_step,
      next_retry_at, requested_at, completed_at
      """;
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcAccountDeletionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountDeletionRecord> findForReplay(UUID profileId, byte[] idempotencyHash) {
    var parameters =
        new MapSqlParameterSource()
            .addValue("profileId", profileId, Types.OTHER)
            .addValue("idempotencyHash", idempotencyHash, Types.BINARY);
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
        new MapSqlParameterSource(
            "lockKey", profileId + ":" + java.util.HexFormat.of().formatHex(idempotencyHash)),
        ignored -> {});
    List<AccountDeletionRecord> rows =
        jdbc.query(
            "select "
                + COLUMNS
                + " from public.account_deletion_requests where user_profile_id=:profileId and idempotency_hash=:idempotencyHash for update",
            parameters,
            this::map);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(AccountDeletionRecord record) {
    int inserted =
        jdbc.update(
            """
            insert into public.account_deletion_requests(
              id, user_profile_id, idempotency_hash, request_hash, status_token_hash,
              status_token_ciphertext, status_token_key_version, status_token_expires_at,
              auth_subject_ciphertext, auth_subject_key_version, status, current_step,
              next_retry_at, requested_at, completed_at)
            values (:id, :profileId, :idempotencyHash, :requestHash, :tokenHash,
              :tokenCiphertext, :tokenKeyVersion, :tokenExpiresAt,
              :subjectCiphertext, :subjectKeyVersion, :status, :currentStep,
              :nextRetryAt, :requestedAt, :completedAt)
            """,
            parameters(record));
    if (inserted != 1) throw new IllegalStateException("account deletion request insert failed");
  }

  @Override
  public Optional<AccountDeletionRecord> findByTokenHash(byte[] statusTokenHash) {
    List<AccountDeletionRecord> rows =
        jdbc.query(
            "select "
                + COLUMNS
                + " from public.account_deletion_requests where status_token_hash=:tokenHash",
            new MapSqlParameterSource().addValue("tokenHash", statusTokenHash, Types.BINARY),
            this::map);
    return rows.stream().findFirst();
  }

  private static MapSqlParameterSource parameters(AccountDeletionRecord record) {
    return new MapSqlParameterSource()
        .addValue("id", record.id())
        .addValue("profileId", record.userProfileId(), Types.OTHER)
        .addValue("idempotencyHash", record.idempotencyHash(), Types.BINARY)
        .addValue("requestHash", record.requestHash(), Types.BINARY)
        .addValue("tokenHash", record.statusTokenHash(), Types.BINARY)
        .addValue("tokenCiphertext", record.statusTokenCiphertext())
        .addValue("tokenKeyVersion", record.statusTokenKeyVersion())
        .addValue("tokenExpiresAt", record.statusTokenExpiresAt())
        .addValue("subjectCiphertext", record.authSubjectCiphertext())
        .addValue("subjectKeyVersion", record.authSubjectKeyVersion())
        .addValue("status", record.status().wireValue())
        .addValue("currentStep", record.currentStep())
        .addValue("nextRetryAt", record.nextRetryAt())
        .addValue("requestedAt", record.requestedAt())
        .addValue("completedAt", record.completedAt());
  }

  private AccountDeletionRecord map(ResultSet rs, int rowNumber) throws SQLException {
    return new AccountDeletionRecord(
        rs.getString("id"),
        rs.getObject("user_profile_id", UUID.class),
        rs.getBytes("idempotency_hash"),
        rs.getBytes("request_hash"),
        rs.getBytes("status_token_hash"),
        rs.getString("status_token_ciphertext"),
        rs.getString("status_token_key_version"),
        instant(rs, "status_token_expires_at"),
        rs.getString("auth_subject_ciphertext"),
        rs.getString("auth_subject_key_version"),
        AccountDeletionStatus.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
        rs.getString("current_step"),
        instant(rs, "next_retry_at"),
        instant(rs, "requested_at"),
        instant(rs, "completed_at"));
  }

  private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
