package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.profile.ProfileProvisioningRequest;
import com.timingjeju.api.application.profile.ProfileProvisioningStore;
import com.timingjeju.api.application.profile.ProvisionedCurrentUser;
import com.timingjeju.api.application.profile.ProvisioningSocialAccount;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProfileProvisioningStore implements ProfileProvisioningStore {

  private static final String EMAIL_CONFLICT_SQL =
      """
      select exists (
        select 1 from public.user_profiles where email = ? and id <> ?
      )
      """;

  private static final String UPSERT_PROFILE_SQL =
      """
      insert into public.user_profiles (
        id, email, nickname, profile_image_url, created_at, updated_at, last_login_at
      ) values (?, ?, ?, ?, ?, ?, ?)
      on conflict (id) do update
      set email = coalesce(public.user_profiles.email, excluded.email),
          nickname = coalesce(public.user_profiles.nickname, excluded.nickname),
          profile_image_url = coalesce(public.user_profiles.profile_image_url, excluded.profile_image_url),
          updated_at = excluded.updated_at,
          last_login_at = excluded.last_login_at
      """;

  private static final String SUBJECT_OWNER_CONFLICT_SQL =
      """
      select exists (
        select 1 from public.social_accounts
        where provider = ? and provider_user_id = ? and user_id <> ?
      )
      """;

  private static final String USER_PROVIDER_SUBJECT_SQL =
      """
      select provider_user_id
      from public.social_accounts
      where user_id = ? and provider = ?
      """;

  private static final String UPSERT_SOCIAL_SQL =
      """
      insert into public.social_accounts (
        user_id, provider, provider_user_id,
        provider_email, provider_nickname, provider_profile_image_url,
        connected_at, last_login_at, revoked_at, raw_profile
      ) values (?, ?, ?, ?, ?, ?, ?, ?, null, '{}'::jsonb)
      on conflict (user_id, provider) do update
      set provider_email = excluded.provider_email,
          provider_nickname = excluded.provider_nickname,
          provider_profile_image_url = excluded.provider_profile_image_url,
          last_login_at = excluded.last_login_at,
          revoked_at = null,
          raw_profile = '{}'::jsonb
      where public.social_accounts.provider_user_id = excluded.provider_user_id
      """;

  private final JdbcTemplate jdbc;

  public JdbcProfileProvisioningStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  @Transactional
  public ProvisionedCurrentUser provision(ProfileProvisioningRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    try {
      rejectEmailOwnedByAnotherUser(request.email(), request.userId());
      upsertProfile(request);
      for (ProvisioningSocialAccount account : request.socialAccounts()) {
        upsertSocial(request.userId(), account, request);
      }
    } catch (DataAccessException failure) {
      throw translate(failure);
    }
    return new ProvisionedCurrentUser(
        request.userId(),
        request.socialAccounts().stream().map(ProvisioningSocialAccount::publicProvider).toList());
  }

  static String contractSql() {
    return String.join(
        "\n",
        EMAIL_CONFLICT_SQL,
        UPSERT_PROFILE_SQL,
        SUBJECT_OWNER_CONFLICT_SQL,
        USER_PROVIDER_SUBJECT_SQL,
        UPSERT_SOCIAL_SQL);
  }

  private void rejectEmailOwnedByAnotherUser(String email, UUID userId) {
    if (email == null) {
      return;
    }
    Boolean conflict = jdbc.queryForObject(EMAIL_CONFLICT_SQL, Boolean.class, email, userId);
    if (Boolean.TRUE.equals(conflict)) {
      throw ProfileProvisioningException.emailConflict();
    }
  }

  private void upsertProfile(ProfileProvisioningRequest request) {
    Timestamp requestedAt = Timestamp.from(request.requestedAt());
    jdbc.update(
        UPSERT_PROFILE_SQL,
        request.userId(),
        request.email(),
        request.nickname(),
        request.profileImageUrl(),
        requestedAt,
        requestedAt,
        requestedAt);
  }

  private void upsertSocial(
      UUID userId, ProvisioningSocialAccount account, ProfileProvisioningRequest request) {
    Boolean subjectConflict =
        jdbc.queryForObject(
            SUBJECT_OWNER_CONFLICT_SQL,
            Boolean.class,
            account.provider(),
            account.providerUserId(),
            userId);
    if (Boolean.TRUE.equals(subjectConflict)) {
      throw ProfileProvisioningException.providerSubjectConflict();
    }
    jdbc
        .query(
            USER_PROVIDER_SUBJECT_SQL,
            (resultSet, rowNumber) -> resultSet.getString("provider_user_id"),
            userId,
            account.provider())
        .stream()
        .filter(existing -> !existing.equals(account.providerUserId()))
        .findAny()
        .ifPresent(ignored -> raiseProviderSubjectConflict());

    Timestamp requestedAt = Timestamp.from(request.requestedAt());
    int affected =
        jdbc.update(
            UPSERT_SOCIAL_SQL,
            userId,
            account.provider(),
            account.providerUserId(),
            account.email(),
            account.nickname(),
            account.profileImageUrl(),
            requestedAt,
            requestedAt);
    if (affected != 1) {
      throw ProfileProvisioningException.providerSubjectConflict();
    }
  }

  private static void raiseProviderSubjectConflict() {
    throw ProfileProvisioningException.providerSubjectConflict();
  }

  static RuntimeException translateForTest(DataAccessException failure) {
    return translate(failure);
  }

  private static ProfileProvisioningException translate(DataAccessException failure) {
    String constraint = uniqueConstraint(failure);
    if ("user_profiles_email_key".equals(constraint)) {
      return ProfileProvisioningException.emailConflict();
    }
    if ("social_accounts_provider_provider_user_id_key".equals(constraint)
        || "social_accounts_user_id_provider_key".equals(constraint)) {
      return ProfileProvisioningException.providerSubjectConflict();
    }
    return ProfileProvisioningException.storageUnavailable();
  }

  private static String uniqueConstraint(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof PSQLException postgres
          && "23505".equals(postgres.getSQLState())
          && postgres.getServerErrorMessage() != null) {
        return postgres.getServerErrorMessage().getConstraint();
      }
      current = current.getCause();
    }
    return null;
  }
}
