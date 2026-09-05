package com.timingjeju.api.application.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.profile.ProfileImageCleanupJob;
import com.timingjeju.api.application.profile.ProfileImageCleanupStore;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageObjectPage;
import com.timingjeju.api.application.profile.ProfileImageOrphanScanCursorStore;
import com.timingjeju.api.application.profile.ProfileImageScanCursor;
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
    catalog.pages.add(
        new ProfileImageObjectPage(
            List.of(OLD, EQUALITY), new ProfileImageScanCursor(0, 2, 0), false));
    catalog.pages.add(
        new ProfileImageObjectPage(
            List.of(RECENT, INVALID), new ProfileImageScanCursor(0, 0, 1), true));
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
                new RecordingCursorStore(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                GRACE,
                2,
                10)
            .scanOnce();

    assertThat(enqueued).isEqualTo(1);
    assertThat(catalog.cursors)
        .containsExactly(ProfileImageScanCursor.initial(), new ProfileImageScanCursor(0, 2, 0));
    assertThat(store.enqueued).extracting(ProfileImageMetadata::objectKey).containsExactly(OLD);
  }

  @Test
  void pagination은_configured_max_pages에서_반드시_중단한다() {
    RecordingCatalog catalog = new RecordingCatalog();
    catalog.pages.add(
        new ProfileImageObjectPage(List.of(OLD), new ProfileImageScanCursor(0, 1, 0), false));
    catalog.pages.add(
        new ProfileImageObjectPage(List.of(OLD), new ProfileImageScanCursor(0, 2, 0), false));
    RecordingCursorStore cursors = new RecordingCursorStore();

    new ProfileImageOrphanService(
            key -> Optional.of(metadata(key, NOW.minus(GRACE).minusSeconds(1))),
            catalog,
            new RecordingStore(),
            cursors,
            Clock.fixed(NOW, ZoneOffset.UTC),
            GRACE,
            1,
            2)
        .scanOnce();

    assertThat(catalog.cursors)
        .containsExactly(ProfileImageScanCursor.initial(), new ProfileImageScanCursor(0, 1, 0));
    assertThat(cursors.current).isEqualTo(new ProfileImageScanCursor(0, 2, 0));
  }

  @Test
  void durable_cursor는_재시작후_1001개_fixed_front_current를_지나_orphan에_도달한다() {
    RecordingCursorStore cursors = new RecordingCursorStore();
    RecordingStore store = new RecordingStore();
    ProfileImageStorageCatalog catalog =
        (cursor, limit) ->
            new ProfileImageObjectPage(
                List.of(cursor.objectOffset() == 1001 ? OLD : RECENT),
                cursor.nextObjects(1),
                false);

    for (int tick = 0; tick <= 1001; tick++) {
      service(catalog, store, cursors, 1).scanOnce();
    }

    assertThat(cursors.current).isEqualTo(new ProfileImageScanCursor(0, 1002, 0));
    assertThat(store.enqueued).extracting(ProfileImageMetadata::objectKey).containsExactly(OLD);
  }

  @Test
  void 앞쪽_delete로_offset이_당겨져_skip되어도_cycle_wrap후_다음_tick에_재방문한다() {
    RecordingCursorStore cursors = new RecordingCursorStore();
    cursors.current = new ProfileImageScanCursor(0, 1, 0);
    RecordingStore store = new RecordingStore();
    RecordingCatalog skipped = new RecordingCatalog();
    skipped.pages.add(
        new ProfileImageObjectPage(List.of(), new ProfileImageScanCursor(1, 0, 0), false));
    skipped.pages.add(
        new ProfileImageObjectPage(List.of(), new ProfileImageScanCursor(0, 0, 1), true));

    service(skipped, store, cursors, 10).scanOnce();

    RecordingCatalog nextCycle = new RecordingCatalog();
    nextCycle.pages.add(
        new ProfileImageObjectPage(List.of(OLD), new ProfileImageScanCursor(0, 1, 1), false));
    service(nextCycle, store, cursors, 1).scanOnce();

    assertThat(nextCycle.cursors).containsExactly(new ProfileImageScanCursor(0, 0, 1));
    assertThat(store.enqueued).extracting(ProfileImageMetadata::objectKey).containsExactly(OLD);
  }

  private static ProfileImageOrphanService service(
      ProfileImageStorageCatalog catalog,
      RecordingStore store,
      RecordingCursorStore cursors,
      int maximumPages) {
    return new ProfileImageOrphanService(
        key -> Optional.of(metadata(key, key.equals(OLD) ? NOW.minus(GRACE).minusSeconds(1) : NOW)),
        catalog,
        store,
        cursors,
        Clock.fixed(NOW, ZoneOffset.UTC),
        GRACE,
        1,
        maximumPages);
  }

  private static String key(String generation) {
    return OWNER + "/profile/" + generation;
  }

  private static ProfileImageMetadata metadata(String key, Instant updatedAt) {
    return new ProfileImageMetadata(key, OWNER, "image/jpeg", 1, "\"etag\"", updatedAt);
  }

  private static final class RecordingCatalog implements ProfileImageStorageCatalog {
    private final List<ProfileImageObjectPage> pages = new ArrayList<>();
    private final List<ProfileImageScanCursor> cursors = new ArrayList<>();

    @Override
    public ProfileImageObjectPage list(ProfileImageScanCursor cursor, int limit) {
      cursors.add(cursor);
      return pages.removeFirst();
    }
  }

  private static final class RecordingCursorStore implements ProfileImageOrphanScanCursorStore {
    private ProfileImageScanCursor current = ProfileImageScanCursor.initial();

    @Override
    public ProfileImageScanCursor load() {
      return current;
    }

    @Override
    public boolean advance(ProfileImageScanCursor expected, ProfileImageScanCursor next) {
      if (!current.equals(expected)) {
        return false;
      }
      current = next;
      return true;
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
