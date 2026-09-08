package com.timingjeju.api.application.profile.service;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStorageObjectDeleter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProfileImageCleanupService {

  private final ProfileImageCleanupStore store;
  private final ProfileImageStorageMetadataReader metadataReader;
  private final ProfileImageStorageObjectDeleter deleter;
  private final Clock clock;
  private final Duration lease;
  private final int batchSize;
  private final Duration retryBase;
  private final Duration retryMaximum;

  public ProfileImageCleanupService(
      ProfileImageCleanupStore store,
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImageStorageObjectDeleter deleter,
      Clock clock,
      Duration lease,
      int batchSize,
      Duration retryBase,
      Duration retryMaximum) {
    this.store = Objects.requireNonNull(store);
    this.metadataReader = Objects.requireNonNull(metadataReader);
    this.deleter = Objects.requireNonNull(deleter);
    this.clock = Objects.requireNonNull(clock);
    this.lease = Objects.requireNonNull(lease);
    this.batchSize = batchSize;
    this.retryBase = Objects.requireNonNull(retryBase);
    this.retryMaximum = Objects.requireNonNull(retryMaximum);
  }

  public int runOnce() {
    Instant now = clock.instant();
    int completed = 0;
    for (ProfileImageCleanupJob job : store.claim(now, lease, batchSize)) {
      boolean succeeded;
      try {
        succeeded = process(job, now);
      } catch (RuntimeException failure) {
        succeeded = false;
      }
      if (succeeded) {
        completed++;
      } else {
        store.retry(job, nextAttempt(job, now));
      }
    }
    return completed;
  }

  private boolean process(ProfileImageCleanupJob job, Instant now) {
    ProfileImageMetadata metadata = metadataReader.read(job.objectKey()).orElse(null);
    if (metadata == null) {
      return store.deleteIfNotCurrent(job, () -> {}, now);
    }
    if (!matches(job, metadata)) {
      return false;
    }
    return store.deleteIfNotCurrent(job, () -> deleter.deleteExact(metadata), now);
  }

  private boolean matches(ProfileImageCleanupJob job, ProfileImageMetadata metadata) {
    return job.ownerId().equals(metadata.ownerId())
        && job.objectKey().equals(metadata.objectKey())
        && job.storageEtag().equals(metadata.storageEtag())
        && metadata.isCleanupSafe();
  }

  private Instant nextAttempt(ProfileImageCleanupJob job, Instant now) {
    int shift = Math.min(Math.max(job.attemptCount() - 1, 0), 30);
    Duration delay;
    try {
      delay = retryBase.multipliedBy(1L << shift);
    } catch (ArithmeticException overflow) {
      delay = retryMaximum;
    }
    if (delay.compareTo(retryMaximum) > 0) {
      delay = retryMaximum;
    }
    return now.plus(delay);
  }
}
