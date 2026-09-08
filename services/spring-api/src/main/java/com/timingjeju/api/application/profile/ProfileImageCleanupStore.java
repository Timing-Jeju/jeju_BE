package com.timingjeju.api.application.profile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ProfileImageCleanupStore {

  List<ProfileImageCleanupJob> claim(Instant now, Duration lease, int limit);

  boolean deleteIfNotCurrent(ProfileImageCleanupJob job, Runnable exactDelete, Instant completedAt);

  void retry(ProfileImageCleanupJob job, Instant nextAttemptAt);

  boolean enqueueOrphanIfUnreferenced(ProfileImageMetadata metadata, Instant enqueuedAt);
}
