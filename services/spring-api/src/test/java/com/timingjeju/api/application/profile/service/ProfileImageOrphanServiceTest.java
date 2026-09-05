package com.timingjeju.api.application.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageObjectPage;
import com.timingjeju.api.application.profile.ProfileImageStorageCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageOrphanServiceTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
  private static final Duration GRACE = Duration.ofHours(24);
  private static final String OLD = key("018f47a1-43d2-7b6e-9fa2-11a1cc32c671");
  private static final String EQUALITY = key("018f47a1-43d2-7b6e-9fa2-11a1cc32c672");
  private static final String RECENT = key("018f47a1-43d2-7b6e-9fa2-11a1cc32c673");
  private static final String INVALID = key("018f47a1-43d2-7b6e-9fa2-11a1cc32c674");

  @Test
  void list_pagination은_끝까지_진행하고_grace보다_strictly_old만_enqueue한다() {
    RecordingCatalog catalog = new RecordingCatalog();
    catalog.pages.add(new ProfileImageObjectPage(List.of(OLD, EQUALITY), true));
    catalog.pages.add(new ProfileImageObjectPage(List.of(RECENT, INVALID), false));
    Map<String, ProfileImageMetadata> metadata =
        Map.of(
            OLD, metadata(OLD, NOW.minus(GRACE).minusMillis(1)),
            EQUALITY, metadata(EQUALITY, NOW.minus(GRACE)),
            RECENT, metadata(RECENT, NOW.minus(GRACE).plusSeconds(1)),
            INVALID,
                new ProfileImageMetadata(
                    INVALID, OWNER, "image/gif", 1, "\"etag\"", NOW.minus(GRACE).minusSeconds(1)));
    RecordingStore store = new RecordingStore();

    int enqueued =
        new ProfileImageOrphanService(
                key -> Optional.ofNullable(metadata.get(key)),
                catalog,
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                GRACE,
                2,
                10)
            .scanOnce();

    assertThat(enqueued).isEqualTo(1);
    assertThat(catalog.offsets).containsExactly(0, 2);
    assertThat(store.enqueued).extracting(ProfileImageMetadata::objectKey).containsExactly(OLD);
  }

  @Test
  void pagination은_configured_max_pages에서_반드시_중단한다() {
    RecordingCatalog catalog = new RecordingCatalog();
    catalog.pages.add(new ProfileImageObjectPage(List.of(OLD), true));
    catalog.pages.add(new ProfileImageObjectPage(List.of(OLD), true));

    new ProfileImageOrphanService(
            key -> Optional.of(metadata(key, NOW.minus(GRACE).minusSeconds(1))),
            catalog,
            new RecordingStore(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            GRACE,
            1,
            2)
        .scanOnce();

    assertThat(catalog.offsets).containsExactly(0, 1);
  }

  private static String key(String generation) {
    return OWNER + "/profile/" + generation;
  }

  private static ProfileImageMetadata metadata(String key, Instant updatedAt) {
    return new ProfileImageMetadata(key, OWNER, "image/jpeg", 1, "\"etag\"", updatedAt);
  }

  private static final class RecordingCatalog implements ProfileImageStorageCatalog {
    private final List<ProfileImageObjectPage> pages = new ArrayList<>();
    private final List<Integer> offsets = new ArrayList<>();

    @Override
    public ProfileImageObjectPage list(int offset, int limit) {
      offsets.add(offset);
      return pages.removeFirst();
    }
  }

  private static final class RecordingStore implements ProfileImageCleanupStore {
    private final List<ProfileImageMetadata> enqueued = new ArrayList<>();

    @Override
    public List<ProfileImageCleanupJob> claim(Instant now, Duration lease, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteIfNotCurrent(
        ProfileImageCleanupJob job, Runnable exactDelete, Instant completedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void retry(ProfileImageCleanupJob job, Instant nextAttemptAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean enqueueOrphanIfUnreferenced(ProfileImageMetadata metadata, Instant enqueuedAt) {
      enqueued.add(metadata);
      return true;
    }
  }
}
