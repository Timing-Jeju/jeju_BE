package com.timingjeju.api.global.security;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
              where (auth_subject_fingerprint = :subjectFingerprint
                    or auth_subject_fingerprint is null)
                and status <> 'cancelled'
            )
            """,
            Map.of("subjectFingerprint", fingerprint(userId)),
            Boolean.class);
    return Boolean.TRUE.equals(pending);
  }

  private static byte[] fingerprint(UUID userId) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(
              userId
                  .toString()
                  .toLowerCase(java.util.Locale.ROOT)
                  .getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
