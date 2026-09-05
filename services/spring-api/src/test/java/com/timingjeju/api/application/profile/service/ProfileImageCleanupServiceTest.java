package com.timingjeju.api.application.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStorageObjectDeleter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageCleanupServiceTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final UUID JOB_ID = UUID.fromString("09000000-0000-4000-8000-000000000078");
  private static final UUID CLAIM_TOKEN = UUID.fromString("09000000-0000-4000-8000-000000000079");
  private static final String KEY = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
  private static final ProfileImageMetadata METADATA =
      new ProfileImageMetadata(KEY, OWNER, "image/webp", 1024, "\"etag-1\"", NOW.minusSeconds(900));

  @Test
  void exact_generation을_검증하고_profile_lock_fence안에서만_delete후_succeed한다() {
    RecordingStore store = new RecordingStore(job("replacement", 1));
    RecordingDeleter deleter = new RecordingDeleter();

    int completed = service(store, key -> Optional.of(METADATA), deleter).runOnce();

    assertThat(completed).isEqualTo(1);
    assertThat(store.claimLease).isEqualTo(Duration.ofMinutes(2));
    assertThat(store.claimLimit).isEqualTo(16);
    assertThat(deleter.deleted).containsExactly(METADATA);
    assertThat(store.retries).isEmpty();
  }

  @Test
  void owner_key_ETag_mismatch와_current_generation은_delete하지_않고_bounded_retry한다() {
    for (ProfileImageMetadata metadata :
        List.of(
            new ProfileImageMetadata(
                KEY,
                UUID.fromString("19000000-0000-4000-8000-000000000001"),
                "image/webp",
                1,
                "\"etag-1\"",
                NOW),
            new ProfileImageMetadata(KEY, OWNER, "image/webp", 1, "\"etag-2\"", NOW))) {
      RecordingStore mismatch = new RecordingStore(job("clear", 3));
      RecordingDeleter deleter = new RecordingDeleter();

      assertThat(service(mismatch, key -> Optional.of(metadata), deleter).runOnce()).isZero();

      assertThat(deleter.deleted).isEmpty();
      assertThat(mismatch.retries).containsExactly(NOW.plus(Duration.ofMinutes(4)));
    }

    RecordingStore current = new RecordingStore(job("orphan", 1));
    current.current = true;
    RecordingDeleter deleter = new RecordingDeleter();
    assertThat(service(current, key -> Optional.of(METADATA), deleter).runOnce()).isZero();
    assertThat(deleter.deleted).isEmpty();
    assertThat(current.retries).containsExactly(NOW.plus(Duration.ofMinutes(1)));
  }

  @Test
  void invalid_MIME_size_metadata는_exact_generation이어도_delete하지_않는다() {
    for (ProfileImageMetadata invalid :
        List.of(
            new ProfileImageMetadata(KEY, OWNER, "image/gif", 1, "\"etag-1\"", NOW),
            new ProfileImageMetadata(KEY, OWNER, "image/webp", 0, "\"etag-1\"", NOW))) {
      RecordingStore store = new RecordingStore(job("orphan", 1));
      RecordingDeleter deleter = new RecordingDeleter();

      assertThat(service(store, key -> Optional.of(invalid), deleter).runOnce()).isZero();
      assertThat(deleter.deleted).isEmpty();
    }
  }

  @Test
  void storage_failure는_claim을_유실하지_않고_retry_maximum으로_cap한다() {
    RecordingStore store = new RecordingStore(job("replacement", 99));

    assertThat(
            service(
                    store,
                    key -> {
                      throw ProfileImageException.storageUnavailable();
                    },
                    metadata -> {
                      throw new AssertionError("delete must not run");
                    })
                .runOnce())
        .isZero();

    assertThat(store.retries).containsExactly(NOW.plus(Duration.ofHours(1)));
  }

  private static ProfileImageCleanupService service(
      RecordingStore store,
      ProfileImageStorageMetadataReader reader,
      ProfileImageStorageObjectDeleter deleter) {
    return new ProfileImageCleanupService(
        store,
        reader,
        deleter,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMinutes(2),
        16,
        Duration.ofMinutes(1),
        Duration.ofHours(1));
  }

  private static ProfileImageCleanupJob job(String reason, int attemptCount) {
    return new ProfileImageCleanupJob(
        JOB_ID, CLAIM_TOKEN, OWNER, KEY, "\"etag-1\"", 7, reason, attemptCount);
  }

  private static final class RecordingDeleter implements ProfileImageStorageObjectDeleter {
    private final List<ProfileImageMetadata> deleted = new ArrayList<>();

    @Override
    public void deleteExact(ProfileImageMetadata expected) {
      deleted.add(expected);
    }
  }

  private static final class RecordingStore implements ProfileImageCleanupStore {
    private final List<ProfileImageCleanupJob> claimed;
    private final List<Instant> retries = new ArrayList<>();
    private boolean current;
    private Duration claimLease;
    private int claimLimit;

    private RecordingStore(ProfileImageCleanupJob job) {
      claimed = List.of(job);
    }

    @Override
    public List<ProfileImageCleanupJob> claim(Instant now, Duration lease, int limit) {
      claimLease = lease;
      claimLimit = limit;
      return claimed;
    }

    @Override
    public boolean deleteIfNotCurrent(
        ProfileImageCleanupJob job, Runnable exactDelete, Instant completedAt) {
      if (current) {
        return false;
      }
      exactDelete.run();
      return true;
    }

    @Override
    public void retry(ProfileImageCleanupJob job, Instant nextAttemptAt) {
      retries.add(nextAttemptAt);
    }

    @Override
    public boolean enqueueOrphanIfUnreferenced(ProfileImageMetadata metadata, Instant enqueuedAt) {
      throw new UnsupportedOperationException();
    }
  }
}
