package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.AuthIdentity;
import com.timingjeju.api.application.profile.AuthIdentityReader;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthIdentityReader implements AuthIdentityReader {

  static final String SELECT_IDENTITIES_SQL =
      """
      select provider,
             provider_id,
             identity_data ->> 'email' as email,
             identity_data ->> 'nickname' as nickname,
             identity_data ->> 'picture' as picture
      from auth.identities
      where user_id = ?
      order by created_at, id
      """;

  private final JdbcTemplate jdbc;

  public JdbcAuthIdentityReader(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public List<AuthIdentity> readByUserId(UUID userId) {
    try {
      return jdbc.query(
          SELECT_IDENTITIES_SQL,
          (resultSet, rowNumber) ->
              new AuthIdentity(
                  resultSet.getString("provider"),
                  resultSet.getString("provider_id"),
                  resultSet.getString("email"),
                  resultSet.getString("nickname"),
                  resultSet.getString("picture")),
          userId);
    } catch (DataAccessException failure) {
      throw ProfileProvisioningException.storageUnavailable();
    }
  }
}
