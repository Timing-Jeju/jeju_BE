package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProfileImageCleanupStore implements ProfileImageCleanupStore {

  private static final String CLAIM_SQL =
      """
      with candidates as (
        select id
        from public.profile_image_cleanup_outbox
        where reason in ('replacement', 'clear', 'orphan')
          and (
            (status in ('pending', 'retry') and next_attempt_at <= ?)
            or (status = 'claimed' and claimed_at <= ?)
          )
        order by next_attempt_at, created_at, id
        for update skip locked
        limit ?
      )
      update public.profile_image_cleanup_outbox outbox
      set status = 'claimed', claim_token = ?, claimed_at = ?,
          attempt_count = attempt_count + 1
      from candidates
      where outbox.id = candidates.id
      returning outbox.id, outbox.claim_token, outbox.owner_user_id, outbox.object_key,
                outbox.storage_etag, outbox.source_profile_version, outbox.reason,
                outbox.attempt_count
      """;
  private static final String LOCK_JOB_SQL =
      """
      select id
      from public.profile_image_cleanup_outbox
      where id = ? and claim_token = ? and status = 'claimed'
        and object_key = ? and storage_etag = ?
      for update
      """;
  private static final String LOCK_PROFILE_SQL =
      """
      select profile_image_object_key, profile_image_storage_etag, profile_image_version
      from public.user_profiles
      where id = ? and status <> 'deleted'
      for update
      """;
  private static final String LOCK_ORPHAN_REFERENCES_SQL =
      """
      select id, profile_image_object_key, profile_image_storage_etag, profile_image_version
      from public.user_profiles
      where status <> 'deleted'
        and (id = ? or profile_image_object_key = ?)
      order by id
      for update
      """;
  private static final String SUCCEED_SQL =
      """
      update public.profile_image_cleanup_outbox
      set status = 'succeeded', claim_token = null, claimed_at = null, completed_at = ?
      where id = ? and claim_token = ? and status = 'claimed'
        and object_key = ? and storage_etag = ?
      """;
  private static final String RETRY_SQL =
      """
      update public.profile_image_cleanup_outbox
      set status = 'retry', claim_token = null, claimed_at = null,
          next_attempt_at = ?, completed_at = null
      where id = ? and claim_token = ? and status = 'claimed'
        and object_key = ? and storage_etag = ?
      """;
  private static final String ENQUEUE_ORPHAN_SQL =
      """
      insert into public.profile_image_cleanup_outbox (
        owner_user_id, object_key, storage_etag, source_profile_version,
        reason, status, next_attempt_at, created_at
      ) values (?, ?, ?, ?, 'orphan', 'pending', ?, ?)
      on conflict (object_key, storage_etag) do nothing
      """;

  private final JdbcTemplate jdbc;

  public JdbcProfileImageCleanupStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc);
  }

  @Override
  @Transactional
  public List<ProfileImageCleanupJob> claim(Instant now, Duration lease, int limit) {
    try {
      UUID claimToken = UUID.randomUUID();
      return jdbc.query(
          CLAIM_SQL,
          (resultSet, rowNumber) -> mapJob(resultSet),
          Timestamp.from(now),
          Timestamp.from(now.minus(lease)),
          limit,
          claimToken,
          Timestamp.from(now));
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @Override
  @Transactional
  public boolean deleteIfNotCurrent(
      ProfileImageCleanupJob job, Runnable exactDelete, Instant completedAt) {
    try {
      requireClaim(job);
      List<CurrentGeneration> profiles =
          jdbc.query(
              LOCK_PROFILE_SQL,
              (resultSet, rowNumber) ->
                  new CurrentGeneration(
                      job.ownerId(),
                      resultSet.getString("profile_image_object_key"),
                      resultSet.getString("profile_image_storage_etag"),
                      resultSet.getLong("profile_image_version")),
              job.ownerId());
      if (!profiles.isEmpty() && profiles.getFirst().matches(job)) {
        return false;
      }
      exactDelete.run();
      int updated =
          jdbc.update(
              SUCCEED_SQL,
              Timestamp.from(completedAt),
              job.id(),
              job.claimToken(),
              job.objectKey(),
              job.storageEtag());
      if (updated != 1) {
        throw ProfileImageException.storageUnavailable();
      }
      return true;
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @Override
  @Transactional
  public void retry(ProfileImageCleanupJob job, Instant nextAttemptAt) {
    try {
      int updated =
          jdbc.update(
              RETRY_SQL,
              Timestamp.from(nextAttemptAt),
              job.id(),
              job.claimToken(),
              job.objectKey(),
              job.storageEtag());
      if (updated != 1) {
        throw ProfileImageException.storageUnavailable();
      }
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @Override
  @Transactional
  public boolean enqueueOrphanIfUnreferenced(ProfileImageMetadata metadata, Instant enqueuedAt) {
    try {
      List<CurrentGeneration> profiles =
          jdbc.query(
              LOCK_ORPHAN_REFERENCES_SQL,
              (resultSet, rowNumber) ->
                  new CurrentGeneration(
                      resultSet.getObject("id", UUID.class),
                      resultSet.getString("profile_image_object_key"),
                      resultSet.getString("profile_image_storage_etag"),
                      resultSet.getLong("profile_image_version")),
              metadata.ownerId(),
              metadata.objectKey());
      if (profiles.stream().anyMatch(profile -> metadata.objectKey().equals(profile.objectKey()))) {
        return false;
      }
      long version =
          profiles.stream()
              .filter(profile -> metadata.ownerId().equals(profile.ownerId()))
              .mapToLong(CurrentGeneration::version)
              .findFirst()
              .orElse(0);
      return jdbc.update(
              ENQUEUE_ORPHAN_SQL,
              metadata.ownerId(),
              metadata.objectKey(),
              metadata.storageEtag(),
              version,
              Timestamp.from(enqueuedAt),
              Timestamp.from(enqueuedAt))
          == 1;
    } catch (DataAccessException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  static String contractSql() {
    return String.join(
        "\n",
        CLAIM_SQL,
        LOCK_JOB_SQL,
        LOCK_PROFILE_SQL,
        LOCK_ORPHAN_REFERENCES_SQL,
        SUCCEED_SQL,
        RETRY_SQL,
        ENQUEUE_ORPHAN_SQL);
  }

  static boolean isCurrentReference(String currentObjectKey, ProfileImageCleanupJob job) {
    return java.util.Objects.equals(currentObjectKey, job.objectKey());
  }

  private void requireClaim(ProfileImageCleanupJob job) {
    List<UUID> rows =
        jdbc.query(
            LOCK_JOB_SQL,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            job.id(),
            job.claimToken(),
            job.objectKey(),
            job.storageEtag());
    if (rows.size() != 1) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private static ProfileImageCleanupJob mapJob(ResultSet resultSet) throws SQLException {
    return new ProfileImageCleanupJob(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("claim_token", UUID.class),
        resultSet.getObject("owner_user_id", UUID.class),
        resultSet.getString("object_key"),
        resultSet.getString("storage_etag"),
        resultSet.getLong("source_profile_version"),
        resultSet.getString("reason"),
        resultSet.getInt("attempt_count"));
  }

  private record CurrentGeneration(
      UUID ownerId, String objectKey, String storageEtag, long version) {
    boolean matches(ProfileImageCleanupJob job) {
      return isCurrentReference(objectKey, job);
    }
  }
}
