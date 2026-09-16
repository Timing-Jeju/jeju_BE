package com.timingjeju.api.domain.accountdeletion.adapter;

import com.timingjeju.api.domain.accountdeletion.port.RecentAuthSessionGateway;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcRecentAuthSessionGateway implements RecentAuthSessionGateway {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcRecentAuthSessionGateway(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean isRecent(UUID userId, UUID sessionId, Instant notBefore) {
    Boolean result =
        jdbc.queryForObject(
            "select public.account_deletion_session_is_recent(:sessionId, :userId, :notBefore)",
            new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue(
                    "notBefore", notBefore.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE),
            Boolean.class);
    return Boolean.TRUE.equals(result);
  }
}
