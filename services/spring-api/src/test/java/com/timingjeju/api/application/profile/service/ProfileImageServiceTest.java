package com.timingjeju.api.application.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileImageCommand;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import com.timingjeju.api.application.profile.ProfileImageSource;
import com.timingjeju.api.application.profile.ProfileImageState;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStore;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfileImageServiceTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);
  private static final String GENERATION = "018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final String KEY = OWNER + "/profile/" + GENERATION;
  private static final Instant NOW = Instant.parse("2026-08-25T11:00:00Z");
  private static final String PUBLIC_URL =
      "https://project.example.invalid/storage/v1/object/public/profile-images/" + KEY;

  @Test
  void GET은_provisioning후_storage_first_snapshot과_version_ETag를_반환한다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(
            new ProfileImageState(
                KEY,
                "\"storage-etag\"",
                "https://provider.example.invalid/avatar",
                ProfileImageSource.STORAGE,
                7,
                NOW));

    ProfileImageSnapshot result = service(provisioning, store, key -> Optional.empty()).read(USER);

    verify(provisioning).provision(USER);
    assertThat(result.profileImageObjectKey()).contains(KEY);
    assertThat(result.profileImageUrl()).contains(PUBLIC_URL);
    assertThat(result.profileImageSource()).isEqualTo(ProfileImageSource.STORAGE);
    assertThat(result.profileImageVersion()).isEqualTo(7);
    assertThat(result.etag()).isEqualTo("\"profile-image-7\"");
  }

  @Test
  void GET의_missing_profile_state는_PROFILE_DATA_UNAVAILABLE이다() {
    RecordingStore store = new RecordingStore();

    assertThatThrownBy(
            () ->
                service(mock(CurrentUserProvisioningService.class), store, key -> Optional.empty())
                    .read(USER))
        .isInstanceOf(CurrentUserProfileException.class)
        .extracting(failure -> ((CurrentUserProfileException) failure).code())
        .isEqualTo("PROFILE_DATA_UNAVAILABLE");
  }

  @Test
  void GET의_storage_URL_projection_failure는_PROFILE_DATA_UNAVAILABLE로_닫힌다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(
            new ProfileImageState(
                KEY, "\"storage-etag\"", null, ProfileImageSource.STORAGE, 1, NOW));
    ProfileImageService service =
        new ProfileImageService(
            mock(CurrentUserProvisioningService.class),
            store,
            key -> Optional.empty(),
            key -> {
              throw ProfileImageException.storageUnavailable();
            },
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.read(USER))
        .isInstanceOf(CurrentUserProfileException.class)
        .extracting(failure -> ((CurrentUserProfileException) failure).code())
        .isEqualTo("PROFILE_DATA_UNAVAILABLE");
  }

  @Test
  void provider_none_state는_storage_key와_ETag가_모두_null이어야_한다() {
    for (Object[] malformed :
        new Object[][] {
          {KEY, null, ProfileImageSource.PROVIDER},
          {null, "\"etag-1\"", ProfileImageSource.NONE}
        }) {
      assertCode(
          () ->
              new ProfileImageState(
                  (String) malformed[0],
                  (String) malformed[1],
                  null,
                  (ProfileImageSource) malformed[2],
                  0,
                  NOW),
          "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
    }
  }

  @Test
  void valid_owner_metadata_confirm은_key_etag_version을_원자_store에_전달한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));
    ProfileImageMetadata metadata =
        new ProfileImageMetadata(KEY, OWNER, "image/webp", 5L * 1024 * 1024, "\"etag-1\"", NOW);

    ProfileImageSnapshot result =
        service(mock(CurrentUserProvisioningService.class), store, key -> Optional.of(metadata))
            .apply(
                USER,
                "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\""));

    assertThat(store.confirmedMetadata).isEqualTo(metadata);
    assertThat(store.expectedVersion).isZero();
    assertThat(result.profileImageVersion()).isEqualTo(1);
  }

  @Test
  void idempotency_key는_1자와_128자_printable_ASCII_경계를_허용한다() {
    for (String idempotencyKey : new String[] {"!", "a".repeat(128)}) {
      RecordingStore store = new RecordingStore();
      store.state =
          Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));

      ProfileImageSnapshot result =
          service(mock(CurrentUserProvisioningService.class), store, key -> Optional.empty())
              .apply(
                  USER,
                  idempotencyKey,
                  ProfileImageCommand.create(OWNER, null, "\"profile-image-0\""));

      assertThat(result.profileImageVersion()).isEqualTo(1);
    }
  }

  @Test
  void idempotency_key는_빈값_129자_control_non_ASCII를_거부한다() {
    for (String idempotencyKey :
        new String[] {"", "a".repeat(129), "line\nbreak", "tab\tkey", "제주"}) {
      assertCode(
          () ->
              service(
                      mock(CurrentUserProvisioningService.class),
                      new RecordingStore(),
                      key -> Optional.empty())
                  .apply(
                      USER,
                      idempotencyKey,
                      ProfileImageCommand.create(OWNER, null, "\"profile-image-0\"")),
          "INVALID_PROFILE_IMAGE_REQUEST");
    }
  }

  @Test
  void confirm은_info_HEAD_metadata를_profile_row_lock전후_두번_독립조회한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));
    ProfileImageMetadata metadata =
        new ProfileImageMetadata(KEY, OWNER, "image/webp", 1, "\"etag-1\"", NOW);
    AtomicInteger reads = new AtomicInteger();

    service(
            mock(CurrentUserProvisioningService.class),
            store,
            key -> {
              reads.incrementAndGet();
              return Optional.of(metadata);
            })
        .apply(
            USER,
            "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
            ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\""));

    assertThat(reads).hasValue(2);
  }

  @Test
  void JPEG_1byte_PNG_5MiB_WebP_경계는_모두_confirm된다() {
    for (Object[] boundary :
        new Object[][] {
          {"image/jpeg", 1L}, {"image/png", 5L * 1024 * 1024}, {"image/webp", 1024L}
        }) {
      RecordingStore store = new RecordingStore();
      store.state =
          Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));
      ProfileImageMetadata metadata =
          new ProfileImageMetadata(
              KEY, OWNER, (String) boundary[0], (Long) boundary[1], "\"etag-1\"", NOW);

      ProfileImageSnapshot result =
          service(mock(CurrentUserProvisioningService.class), store, key -> Optional.of(metadata))
              .apply(
                  USER,
                  "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                  ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\""));

      assertThat(result.profileImageSource()).isEqualTo(ProfileImageSource.STORAGE);
    }
  }

  @Test
  void missing_or_wrong_owner는_동일한_404로_은닉한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));

    assertCode(
        () ->
            service(mock(CurrentUserProvisioningService.class), store, key -> Optional.empty())
                .apply(
                    USER,
                    "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                    ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\"")),
        "PROFILE_IMAGE_NOT_FOUND");

    ProfileImageMetadata otherOwner =
        new ProfileImageMetadata(
            KEY,
            UUID.fromString("19000000-0000-4000-8000-000000000001"),
            "image/jpeg",
            1,
            "\"etag-1\"",
            NOW);
    assertCode(
        () ->
            service(
                    mock(CurrentUserProvisioningService.class),
                    store,
                    key -> Optional.of(otherOwner))
                .apply(
                    USER,
                    "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                    ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\"")),
        "PROFILE_IMAGE_NOT_FOUND");
  }

  @Test
  void storage_adapter의_owner_mismatch_404는_service에서도_그대로_은닉한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));

    assertCode(
        () ->
            service(
                    mock(CurrentUserProvisioningService.class),
                    store,
                    key -> {
                      throw ProfileImageException.notFound();
                    })
                .apply(
                    USER,
                    "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                    ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\"")),
        "PROFILE_IMAGE_NOT_FOUND");
  }

  @Test
  void size와_MIME_경계를_fail_closed로_검증한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));

    assertMetadataCode(store, "image/jpeg", 0, "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
    assertMetadataCode(store, "image/png", 5L * 1024 * 1024 + 1, "PROFILE_IMAGE_TOO_LARGE");
    assertMetadataCode(store, "image/gif", 1, "PROFILE_IMAGE_MEDIA_TYPE_UNSUPPORTED");
    assertMetadataCode(store, null, 1, "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
  }

  @Test
  void weak_or_missing_storage_ETag는_503으로_fail_closed한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(new ProfileImageState(null, null, null, ProfileImageSource.NONE, 0, NOW));
    for (String etag : new String[] {"W/\"etag\"", "etag", "", "\""}) {
      ProfileImageMetadata metadata =
          new ProfileImageMetadata(KEY, OWNER, "image/jpeg", 1, etag, NOW);
      assertCode(
          () ->
              service(
                      mock(CurrentUserProvisioningService.class),
                      store,
                      key -> Optional.of(metadata))
                  .apply(
                      USER,
                      "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                      ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\"")),
          "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
    }
  }

  @Test
  void clear는_storage_lookup없이_provider_fallback으로_전환한다() {
    RecordingStore store = new RecordingStore();
    store.state =
        Optional.of(
            new ProfileImageState(
                KEY,
                "\"etag-1\"",
                "https://provider.example.invalid/avatar",
                ProfileImageSource.STORAGE,
                1,
                NOW));

    ProfileImageSnapshot result =
        service(
                mock(CurrentUserProvisioningService.class),
                store,
                key -> {
                  throw new AssertionError("clear must not read Storage");
                })
            .apply(
                USER,
                "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                ProfileImageCommand.create(OWNER, null, "\"profile-image-1\""));

    assertThat(store.clearCalled).isTrue();
    assertThat(result.profileImageSource()).isEqualTo(ProfileImageSource.PROVIDER);
    assertThat(result.profileImageVersion()).isEqualTo(2);
  }

  private static ProfileImageService service(
      CurrentUserProvisioningService provisioning,
      RecordingStore store,
      ProfileImageStorageMetadataReader metadataReader) {
    return new ProfileImageService(
        provisioning, store, metadataReader, key -> PUBLIC_URL, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static void assertMetadataCode(
      RecordingStore store, String mime, long size, String expectedCode) {
    ProfileImageMetadata metadata =
        new ProfileImageMetadata(KEY, OWNER, mime, size, "\"etag-1\"", NOW);
    assertCode(
        () ->
            service(mock(CurrentUserProvisioningService.class), store, key -> Optional.of(metadata))
                .apply(
                    USER,
                    "018f47a1-43d2-7b6e-9fa2-11a1cc32c675",
                    ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\"")),
        expectedCode);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo(code);
  }

  private static final class RecordingStore implements ProfileImageStore {
    private Optional<ProfileImageState> state = Optional.empty();
    private ProfileImageMetadata confirmedMetadata;
    private long expectedVersion;
    private boolean clearCalled;

    @Override
    public Optional<ProfileImageState> read(UUID ownerId) {
      return state;
    }

    @Override
    public ProfileImageState confirm(
        UUID ownerId,
        long version,
        ProfileImageMetadata metadata,
        Supplier<ProfileImageMetadata> lockedMetadataRecheck,
        Instant changedAt) {
      expectedVersion = version;
      confirmedMetadata = metadata;
      assertThat(lockedMetadataRecheck.get()).isEqualTo(metadata);
      return new ProfileImageState(
          metadata.objectKey(),
          metadata.storageEtag(),
          null,
          ProfileImageSource.STORAGE,
          version + 1,
          changedAt);
    }

    @Override
    public ProfileImageState clear(UUID ownerId, long version, Instant changedAt) {
      clearCalled = true;
      return new ProfileImageState(
          null,
          null,
          "https://provider.example.invalid/avatar",
          ProfileImageSource.PROVIDER,
          version + 1,
          changedAt);
    }
  }
}
