package com.timingjeju.api.application.profile;

import java.util.Set;
import java.util.UUID;

public record ProfileImageCleanupJob(
    UUID id,
    UUID claimToken,
    UUID ownerId,
    String objectKey,
    String storageEtag,
    long sourceProfileVersion,
    String reason,
    int attemptCount) {

  private static final Set<String> ISSUE_78_REASONS = Set.of("replacement", "clear", "orphan");

  public ProfileImageCleanupJob {
    if (id == null
        || claimToken == null
        || ownerId == null
        || objectKey == null
        || storageEtag == null
        || sourceProfileVersion < 0
        || !ISSUE_78_REASONS.contains(reason)
        || attemptCount < 1) {
      throw ProfileImageException.storageUnavailable();
    }
  }
}
