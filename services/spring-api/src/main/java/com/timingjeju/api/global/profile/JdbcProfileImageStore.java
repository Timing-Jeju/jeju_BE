package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageSource;
import com.timingjeju.api.application.profile.ProfileImageState;
import com.timingjeju.api.application.profile.ProfileImageStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProfileImageStore implements ProfileImageStore {

  private static final String READ_SQL =
      """
      select profile_image_object_key, profile_image_source, profile_image_storage_etag,
             profile_image_version, profile_image_url, updated_at
      from public.user_profiles
      where id = ? and status <> 'deleted'
      """;
  private static final String LOCK_SQL = READ_SQL + " for update";
  private static final String READ_PROVIDER_IMAGES_SQL =
      """
      select provider, provider_profile_image_url
      from public.social_accounts
      where user_id = ? and revoked_at is null
      order by case provider when 'google' then 1 when 'kakao' then 2 when 'naver' then 3 else 4 end,
               provider
      """;

  private final JdbcTemplate jdbc;

  public JdbcProfileImageStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ProfileImageState> read(UUID ownerId) {
    try {
      return unique(READ_SQL, ownerId);
    } catch (DataAccessException failure) {
      throw CurrentUserProfileException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public ProfileImageState confirm(
      UUID ownerId,
      long expectedVersion,
      ProfileImageMetadata metadata,
      Supplier<ProfileImageMetadata> lockedMetadataRecheck,
      Instant changedAt) {
    try {
      ProfileImageState current = locked(ownerId, expectedVersion);
      ProfileImageMetadata rechecked = lockedMetadataRecheck.get();
      requireSameGeneration(metadata, rechecked);
      if (current.source() == ProfileImageSource.STORAGE
          && (!current.objectKey().equals(metadata.objectKey())
              || !current.storageEtag().equals(metadata.storageEtag()))) {
        enqueue(ownerId, current, "replacement", changedAt);
      }
      int updated =
          jdbc.update(
              """
              update public.user_profiles
              set profile_image_object_key = ?, profile_image_source = 'storage',
                  profile_image_storage_etag = ?, profile_image_version = ?, updated_at = ?
              where id = ? and profile_image_version = ? and status <> 'deleted'
              """,
              metadata.objectKey(),
              metadata.storageEtag(),
              nextVersion(current.version()),
              Timestamp.from(changedAt),
              ownerId,
              expectedVersion);
      if (updated != 1) {
        throw ProfileImageException.versionConflict();
      }
      return locked(ownerId, nextVersion(current.version()));
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @Override
  @Transactional
  public ProfileImageState clear(UUID ownerId, long expectedVersion, Instant changedAt) {
    try {
      ProfileImageState current = locked(ownerId, expectedVersion);
      if (current.source() == ProfileImageSource.STORAGE) {
        enqueue(ownerId, current, "clear", changedAt);
      }
      long next = nextVersion(current.version());
      String fallbackSource = current.providerImageUrl() == null ? "none" : "provider";
      int updated =
          jdbc.update(
              """
              update public.user_profiles
              set profile_image_object_key = null,
                  profile_image_source = ?,
                  profile_image_storage_etag = null, profile_image_version = ?, updated_at = ?
              where id = ? and profile_image_version = ? and status <> 'deleted'
              """,
              fallbackSource,
              next,
              Timestamp.from(changedAt),
              ownerId,
              expectedVersion);
      if (updated != 1) {
        throw ProfileImageException.versionConflict();
      }
      return locked(ownerId, next);
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  static String contractSql() {
    return String.join("\n", READ_SQL, LOCK_SQL, READ_PROVIDER_IMAGES_SQL);
  }

  static void requireSameGeneration(
      ProfileImageMetadata beforeLock, ProfileImageMetadata afterLock) {
    if (!beforeLock.objectKey().equals(afterLock.objectKey())
        || !beforeLock.storageEtag().equals(afterLock.storageEtag())) {
      throw ProfileImageException.versionConflict();
    }
  }

  private ProfileImageState locked(UUID ownerId, long expectedVersion) {
    ProfileImageState state =
        unique(LOCK_SQL, ownerId).orElseThrow(ProfileImageException::storageUnavailable);
    if (state.version() != expectedVersion) {
      throw ProfileImageException.versionConflict();
    }
    return state;
  }

  private Optional<ProfileImageState> unique(String sql, UUID ownerId) {
    List<StoredProfileImage> rows =
        jdbc.query(sql, (resultSet, rowNumber) -> mapStored(resultSet), ownerId);
    if (rows.size() != 1) {
      return Optional.empty();
    }
    StoredProfileImage row = rows.getFirst();
    ProfileImageProviderFallback.Selection fallback =
        readProviderFallback(ownerId, row.legacyProviderImageUrl());
    ProfileImageSource effectiveSource =
        row.persistedSource() == ProfileImageSource.STORAGE
            ? ProfileImageSource.STORAGE
            : fallback.source();
    return Optional.of(row.toState(fallback.imageUrl(), effectiveSource));
  }

  private ProfileImageProviderFallback.Selection readProviderFallback(
      UUID ownerId, String legacyProviderImageUrl) {
    List<ProfileImageProviderFallback.Candidate> candidates =
        jdbc.query(
            READ_PROVIDER_IMAGES_SQL,
            (resultSet, rowNumber) ->
                new ProfileImageProviderFallback.Candidate(
                    resultSet.getString("provider"),
                    resultSet.getString("provider_profile_image_url")),
            ownerId);
    return ProfileImageProviderFallback.resolve(candidates, legacyProviderImageUrl);
  }

  private void enqueue(UUID ownerId, ProfileImageState previous, String reason, Instant changedAt) {
    jdbc.update(
        """
        insert into public.profile_image_cleanup_outbox (
          owner_user_id, object_key, storage_etag, source_profile_version,
          reason, status, next_attempt_at, created_at
        ) values (?, ?, ?, ?, ?, 'pending', ?, ?)
        on conflict (object_key, storage_etag) do nothing
        """,
        ownerId,
        previous.objectKey(),
        previous.storageEtag(),
        previous.version(),
        reason,
        Timestamp.from(changedAt),
        Timestamp.from(changedAt));
  }

  private static StoredProfileImage mapStored(ResultSet resultSet) throws SQLException {
    return new StoredProfileImage(
        resultSet.getString("profile_image_object_key"),
        resultSet.getString("profile_image_storage_etag"),
        resultSet.getString("profile_image_url"),
        ProfileImageSource.fromDatabase(resultSet.getString("profile_image_source")),
        resultSet.getLong("profile_image_version"),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private record StoredProfileImage(
      String objectKey,
      String storageEtag,
      String legacyProviderImageUrl,
      ProfileImageSource persistedSource,
      long version,
      Instant updatedAt) {

    ProfileImageState toState(String providerImageUrl, ProfileImageSource effectiveSource) {
      return new ProfileImageState(
          objectKey, storageEtag, providerImageUrl, effectiveSource, version, updatedAt);
    }
  }

  private static long nextVersion(long current) {
    if (current == Long.MAX_VALUE) {
      throw ProfileImageException.versionConflict();
    }
    return current + 1;
  }
}
