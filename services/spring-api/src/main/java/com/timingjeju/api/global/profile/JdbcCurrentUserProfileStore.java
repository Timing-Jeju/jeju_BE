package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.ProfilePatchCommand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCurrentUserProfileStore implements CurrentUserProfileStore {

  private static final String READ_PROFILE_SQL =
      """
      select id, email, nickname, profile_image_url, locale,
             onboarding_completed_at, updated_at
      from public.user_profiles
      where id = ? and status <> 'deleted'
      """;

  private static final String READ_PROVIDERS_SQL =
      """
      select provider
      from public.social_accounts
      where user_id = ? and revoked_at is null
      order by provider
      """;

  private static final String UPDATE_PROFILE_SQL =
      """
      update public.user_profiles
      set nickname = case when ? then ? else nickname end,
          locale = case when ? then ? else locale end,
          updated_at = ?
      where id = ? and status <> 'deleted'
      """;

  private final JdbcTemplate jdbc;

  public JdbcCurrentUserProfileStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CurrentUserProfile> read(UUID userId) {
    try {
      List<ProfileRow> rows =
          jdbc.query(READ_PROFILE_SQL, (resultSet, rowNumber) -> profileRow(resultSet), userId);
      if (rows.size() != 1) {
        return Optional.empty();
      }
      List<String> providers =
          jdbc.query(
              READ_PROVIDERS_SQL,
              (resultSet, rowNumber) -> publicProvider(resultSet.getString("provider")),
              userId);
      ProfileRow row = rows.getFirst();
      return Optional.of(row.toProfile(providers));
    } catch (DataAccessException failure) {
      throw CurrentUserProfileException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public CurrentUserProfile update(UUID userId, ProfilePatchCommand command, Instant updatedAt) {
    try {
      int affected =
          jdbc.update(
              UPDATE_PROFILE_SQL,
              command.nicknamePresent(),
              command.nickname(),
              command.localePresent(),
              command.locale(),
              Timestamp.from(updatedAt),
              userId);
      if (affected != 1) {
        throw CurrentUserProfileException.dataUnavailable();
      }
      return read(userId).orElseThrow(CurrentUserProfileException::dataUnavailable);
    } catch (DataAccessException failure) {
      throw CurrentUserProfileException.dataUnavailable();
    }
  }

  static String contractSql() {
    return String.join("\n", READ_PROFILE_SQL, READ_PROVIDERS_SQL, UPDATE_PROFILE_SQL);
  }

  private static ProfileRow profileRow(ResultSet resultSet) throws SQLException {
    Timestamp onboarding = resultSet.getTimestamp("onboarding_completed_at");
    return new ProfileRow(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("email"),
        resultSet.getString("nickname"),
        resultSet.getString("profile_image_url"),
        resultSet.getString("locale"),
        onboarding != null,
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static String publicProvider(String provider) {
    return "naver".equals(provider) ? "custom:naver" : provider;
  }

  private record ProfileRow(
      UUID userId,
      String email,
      String nickname,
      String profileImageUrl,
      String locale,
      boolean onboardingCompleted,
      Instant updatedAt) {

    CurrentUserProfile toProfile(List<String> providers) {
      return new CurrentUserProfile(
          userId,
          email,
          nickname,
          profileImageUrl,
          locale,
          providers,
          onboardingCompleted,
          updatedAt);
    }
  }
}
