package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.CurrentUserProfile;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImagePublicUrl;
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
      select id, email, nickname, profile_image_url, profile_image_object_key,
             profile_image_source, locale,
             onboarding_completed_at, updated_at
      from public.user_profiles
      where id = ? and status <> 'deleted'
      """;

  private static final String READ_PROVIDERS_SQL =
      """
      select provider, provider_profile_image_url
      from public.social_accounts
      where user_id = ? and revoked_at is null
      order by case provider when 'google' then 1 when 'kakao' then 2 when 'naver' then 3 else 4 end,
               provider
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
  private final ProfileImagePublicUrl profileImagePublicUrl;

  public JdbcCurrentUserProfileStore(
      JdbcTemplate jdbc, ProfileImagePublicUrl profileImagePublicUrl) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.profileImagePublicUrl =
        java.util.Objects.requireNonNull(
            profileImagePublicUrl, "profileImagePublicUrl must not be null");
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
      List<ProviderAccount> accounts =
          jdbc.query(
              READ_PROVIDERS_SQL,
              (resultSet, rowNumber) ->
                  new ProviderAccount(
                      resultSet.getString("provider"),
                      resultSet.getString("provider_profile_image_url")),
              userId);
      List<String> providers =
          accounts.stream()
              .map(ProviderAccount::provider)
              .map(JdbcCurrentUserProfileStore::publicProvider)
              .toList();
      ProfileRow row = rows.getFirst();
      ProfileImageProviderFallback.Selection fallback =
          ProfileImageProviderFallback.resolve(
              accounts.stream()
                  .map(
                      account ->
                          new ProfileImageProviderFallback.Candidate(
                              account.provider(), account.imageUrl()))
                  .toList(),
              row.legacyProviderImageUrl());
      return Optional.of(row.toProfile(providers, fallback, profileImagePublicUrl));
    } catch (DataAccessException | ProfileImageException failure) {
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
        resultSet.getString("profile_image_object_key"),
        resultSet.getString("profile_image_source"),
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
      String legacyProviderImageUrl,
      String profileImageObjectKey,
      String profileImageSource,
      String locale,
      boolean onboardingCompleted,
      Instant updatedAt) {

    CurrentUserProfile toProfile(
        List<String> providers,
        ProfileImageProviderFallback.Selection fallback,
        ProfileImagePublicUrl profileImagePublicUrl) {
      String resolvedImageUrl =
          "storage".equals(profileImageSource)
              ? profileImagePublicUrl.resolve(profileImageObjectKey)
              : fallback.imageUrl();
      return new CurrentUserProfile(
          userId,
          email,
          nickname,
          resolvedImageUrl,
          locale,
          providers,
          onboardingCompleted,
          updatedAt);
    }
  }

  private record ProviderAccount(String provider, String imageUrl) {}
}
