package com.timingjeju.api.application.profile.service;

import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageObjectPage;
import com.timingjeju.api.application.profile.ProfileImageOrphanScanCursorStore;
import com.timingjeju.api.application.profile.ProfileImageScanCursor;
import com.timingjeju.api.application.profile.ProfileImageStorageCatalog;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ProfileImageOrphanService {

  private final ProfileImageStorageMetadataReader metadataReader;
  private final ProfileImageStorageCatalog catalog;
  private final ProfileImageCleanupStore store;
  private final ProfileImageOrphanScanCursorStore cursors;
  private final Clock clock;
  private final Duration grace;
  private final int pageSize;
  private final int maximumPages;

  public ProfileImageOrphanService(
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImageStorageCatalog catalog,
      ProfileImageCleanupStore store,
      ProfileImageOrphanScanCursorStore cursors,
      Clock clock,
      Duration grace,
      int pageSize,
      int maximumPages) {
    this.metadataReader = Objects.requireNonNull(metadataReader);
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.cursors = Objects.requireNonNull(cursors);
    this.clock = Objects.requireNonNull(clock);
    this.grace = Objects.requireNonNull(grace);
    this.pageSize = pageSize;
    this.maximumPages = maximumPages;
  }

  public int scanOnce() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(grace);
    int enqueued = 0;
    ProfileImageScanCursor expected = cursors.load();
    ProfileImageScanCursor cursor = expected;
    for (int pageNumber = 0; pageNumber < maximumPages; pageNumber++) {
      ProfileImageObjectPage page = catalog.list(cursor, pageSize);
      for (String objectKey : page.objectKeys()) {
        try {
          ProfileImageMetadata metadata = metadataReader.read(objectKey).orElse(null);
          if (metadata != null
              && metadata.isCleanupSafe()
              && metadata.updatedAt().isBefore(cutoff)
              && store.enqueueOrphanIfUnreferenced(metadata, now)) {
            enqueued++;
          }
        } catch (RuntimeException failure) {
          // A malformed or concurrently removed generation is never enqueued or deleted.
        }
      }
      cursor = page.nextCursor();
      if (page.cycleComplete()) {
        break;
      }
    }
    if (!cursors.advance(expected, cursor)) {
      throw com.timingjeju.api.application.profile.ProfileImageException.storageUnavailable();
    }
    return enqueued;
  }
}
