package com.timingjeju.api.application.profile.service;

import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileImageApplyResult;
import com.timingjeju.api.application.profile.ProfileImageCommand;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageMutationExecutor;
import com.timingjeju.api.application.profile.ProfileImagePublicUrl;
import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import com.timingjeju.api.application.profile.ProfileImageSource;
import com.timingjeju.api.application.profile.ProfileImageState;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStore;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ProfileImageService {

  private static final long MAX_BYTES = 5L * 1024 * 1024;
  private static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
  private static final Pattern STRONG_STORAGE_ETAG =
      Pattern.compile("^\\\"[^\\\"\\p{Cntrl}]+\\\"$");
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[\\x20-\\x7E]{1,128}$");

  private final CurrentUserProvisioningService provisioning;
  private final ProfileImageStore profiles;
  private final ProfileImageStorageMetadataReader metadataReader;
  private final ProfileImagePublicUrl publicUrl;
  private final ProfileImageMutationExecutor mutations;
  private final Clock clock;

  public ProfileImageService(
      CurrentUserProvisioningService provisioning,
      ProfileImageStore profiles,
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImagePublicUrl publicUrl,
      Clock clock) {
    this(
        provisioning,
        profiles,
        metadataReader,
        publicUrl,
        (ownerId, key, command, mutation) -> new ProfileImageApplyResult(mutation.get(), false),
        clock);
  }

  public ProfileImageService(
      CurrentUserProvisioningService provisioning,
      ProfileImageStore profiles,
      ProfileImageStorageMetadataReader metadataReader,
      ProfileImagePublicUrl publicUrl,
      ProfileImageMutationExecutor mutations,
      Clock clock) {
    this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
    this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader must not be null");
    this.publicUrl = Objects.requireNonNull(publicUrl, "publicUrl must not be null");
    this.mutations = Objects.requireNonNull(mutations, "mutations must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ProfileImageSnapshot read(CurrentUser currentUser) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    provisioning.provision(currentUser);
    try {
      return project(
          profiles
              .read(currentUser.userId())
              .orElseThrow(CurrentUserProfileException::dataUnavailable));
    } catch (ProfileImageException failure) {
      throw CurrentUserProfileException.dataUnavailable();
    }
  }

  public ProfileImageSnapshot apply(
      CurrentUser currentUser, String idempotencyKey, ProfileImageCommand command) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    Objects.requireNonNull(command, "command must not be null");
    requireCanonicalIdempotencyKey(idempotencyKey);
    provisioning.provision(currentUser);
    ProfileImageState changed =
        command
            .objectKey()
            .map(key -> confirm(currentUser.userId(), command.expectedVersion(), key))
            .orElseGet(
                () ->
                    profiles.clear(
                        currentUser.userId(), command.expectedVersion(), clock.instant()));
    return project(changed);
  }

  public ProfileImageApplyResult applyIdempotent(
      CurrentUser currentUser, String idempotencyKey, ProfileImageCommand command) {
    requireCanonicalIdempotencyKey(idempotencyKey);
    return mutations.execute(
        currentUser.userId(),
        idempotencyKey,
        command,
        () -> apply(currentUser, idempotencyKey, command));
  }

  private ProfileImageState confirm(UUID ownerId, long expectedVersion, String objectKey) {
    ProfileImageMetadata metadata =
        validateMetadata(
            ownerId,
            objectKey,
            metadataReader.read(objectKey).orElseThrow(ProfileImageException::notFound));
    return profiles.confirm(
        ownerId,
        expectedVersion,
        metadata,
        () ->
            validateMetadata(
                ownerId,
                objectKey,
                metadataReader.read(objectKey).orElseThrow(ProfileImageException::notFound)),
        clock.instant());
  }

  private ProfileImageMetadata validateMetadata(
      UUID ownerId, String objectKey, ProfileImageMetadata metadata) {
    if (!metadata.objectKey().equals(objectKey) || !metadata.ownerId().equals(ownerId)) {
      throw ProfileImageException.notFound();
    }
    if (metadata.sizeBytes() < 1) {
      throw ProfileImageException.storageUnavailable();
    }
    if (metadata.sizeBytes() > MAX_BYTES) {
      throw ProfileImageException.tooLarge();
    }
    if (metadata.contentType() == null) {
      throw ProfileImageException.storageUnavailable();
    }
    if (!MEDIA_TYPES.contains(metadata.contentType())) {
      throw ProfileImageException.mediaTypeUnsupported();
    }
    String etag = metadata.storageEtag();
    if (etag == null || !STRONG_STORAGE_ETAG.matcher(etag).matches()) {
      throw ProfileImageException.storageUnavailable();
    }
    return metadata;
  }

  private ProfileImageSnapshot project(ProfileImageState state) {
    String imageUrl =
        state.source() == ProfileImageSource.STORAGE
            ? publicUrl.resolve(state.objectKey())
            : state.providerImageUrl();
    return new ProfileImageSnapshot(
        state.objectKey(), imageUrl, state.source(), state.version(), state.updatedAt());
  }

  private static void requireCanonicalIdempotencyKey(String value) {
    if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
      throw ProfileImageException.invalidRequest();
    }
  }
}
