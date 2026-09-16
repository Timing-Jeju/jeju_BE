package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AppOwnedDataErasure;
import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionLease;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Executes only local, bounded database mutations; no network call runs inside this transaction.
 */
public final class JdbcAppOwnedDataErasure implements AppOwnedDataErasure {
  private static final String LOCK_FENCED_REQUEST =
      """
      select id
      from public.account_deletion_requests
      where id = :requestId
        and status = 'running'
        and lease_owner = :owner
        and fencing_token = :fence
        and lease_expires_at > clock_timestamp()
        and (user_profile_id is null or user_profile_id = :userId)
      for update
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionOperations transaction;

  public JdbcAppOwnedDataErasure(
      NamedParameterJdbcTemplate jdbc, TransactionOperations transaction) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
    this.transaction = java.util.Objects.requireNonNull(transaction);
  }

  @Override
  public void deleteAndAnonymize(DeletionLease lease, AuthSubject subject) {
    UUID subjectId = canonicalUuid(subject);
    transaction.executeWithoutResult(
        ignored -> {
          Map<String, Object> params =
              Map.of(
                  "requestId", lease.requestId(),
                  "owner", lease.owner(),
                  "fence", lease.fencingToken(),
                  "userId", subjectId);
          String requestId = lockRequest(params);
          if (!lease.requestId().equals(requestId)
              || jdbc.update(
                      """
                      update public.account_deletion_requests
                      set current_step = current_step
                      where id = :requestId and status = 'running'
                        and lease_owner = :owner and fencing_token = :fence
                        and lease_expires_at > clock_timestamp()
                      """,
                      params)
                  != 1) {
            throw DeletionOperationException.terminal("ACCOUNT_DELETION_LEASE_LOST");
          }
          // Plans must precede sessions because trip_plans requires either user_id or session_id.
          jdbc.update("delete from public.trip_plans where user_id = :userId", params);
          jdbc.update("delete from public.app_sessions where user_id = :userId", params);
          jdbc.update(
              "delete from public.api_idempotency_records where owner_sub = :userId", params);
          jdbc.update(
              "delete from public.saved_place_idempotency where owner_sub = :userId", params);
          jdbc.update(
              "delete from public.accommodation_idempotency where owner_sub = :userId", params);
          jdbc.update(
              "delete from public.profile_image_cleanup_outbox where owner_user_id = :userId",
              params);
          jdbc.update("delete from public.social_accounts where user_id = :userId", params);
          jdbc.update("delete from public.saved_places where user_id = :userId", params);
          jdbc.update(
              "delete from public.saved_places_backfill_audit where user_id = :userId", params);
          jdbc.update("delete from public.ai_conversations where user_id = :userId", params);
          jdbc.update(
              "update public.user_consents set user_id = null where user_id = :userId", params);
          anonymizeLegacyMcpUser(params);
          jdbc.update(
              """
              update public.user_profiles
              set email = null,
                  nickname = null,
                  profile_image_url = null,
                  locale = 'ko-KR',
                  status = 'deleted',
                  onboarding_completed_at = null,
                  last_login_at = null,
                  deleted_at = coalesce(deleted_at, now()),
                  updated_at = now()
              where id = :userId
              """,
              params);
        });
  }

  private String lockRequest(Map<String, Object> params) {
    try {
      return jdbc.queryForObject(LOCK_FENCED_REQUEST, params, String.class);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private void anonymizeLegacyMcpUser(Map<String, Object> params) {
    Boolean legacyUserColumn =
        jdbc.queryForObject(
            """
            select exists (
              select 1 from information_schema.columns
              where table_schema = 'public' and table_name = 'mcp_compute_call_logs'
                and column_name = 'user_id'
            )
            """,
            Map.of(),
            Boolean.class);
    if (Boolean.TRUE.equals(legacyUserColumn)) {
      jdbc.update(
          "update public.mcp_compute_call_logs set user_id = null where user_id = :userId", params);
    }
  }

  private static UUID canonicalUuid(AuthSubject subject) {
    try {
      UUID value = UUID.fromString(java.util.Objects.requireNonNull(subject).value());
      if (!value.toString().equals(subject.value())) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("INVALID_AUTH_SUBJECT");
    }
  }
}
