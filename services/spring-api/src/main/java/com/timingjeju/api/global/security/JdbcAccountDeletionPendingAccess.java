package com.timingjeju.api.global.security;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAccountDeletionPendingAccess implements AccountDeletionPendingAccess {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcAccountDeletionPendingAccess(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
  }

  @Override
  public boolean isPending(UUID userId) {
    Boolean pending =
        jdbc.queryForObject(
            """
            select exists (
              select 1
              from public.account_deletion_requests
              where user_profile_id = :userId
                and status in ('queued', 'running')
            )
            """,
            Map.of("userId", userId),
            Boolean.class);
    return Boolean.TRUE.equals(pending);
  }
}
