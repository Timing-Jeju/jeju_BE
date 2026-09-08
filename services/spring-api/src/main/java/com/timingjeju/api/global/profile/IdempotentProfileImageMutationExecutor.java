package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.profile.ProfileImageApplyResult;
import com.timingjeju.api.application.profile.ProfileImageCommand;
import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMutationExecutor;
import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

final class IdempotentProfileImageMutationExecutor implements ProfileImageMutationExecutor {

  private static final String PATH = "/api/v1/me/profile-image";
  private final IdempotencyUseCase idempotency;
  private final ObjectMapper objectMapper;

  IdempotentProfileImageMutationExecutor(
      IdempotencyUseCase idempotency, ObjectMapper objectMapper) {
    this.idempotency = java.util.Objects.requireNonNull(idempotency);
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
  }

  @Override
  public ProfileImageApplyResult execute(
      UUID ownerId,
      String idempotencyKey,
      ProfileImageCommand command,
      Supplier<ProfileImageSnapshot> mutation) {
    byte[] canonicalBody =
        (command.expectedVersion() + "\n" + command.objectKey().orElse("null"))
            .getBytes(StandardCharsets.UTF_8);
    IdempotencyRequest request =
        IdempotencyRequest.create(ownerId, "PUT", PATH, idempotencyKey, canonicalBody);
    AtomicBoolean executed = new AtomicBoolean();
    IdempotencyResponse response =
        idempotency.execute(
            request,
            () -> {
              executed.set(true);
              ProfileImageSnapshot snapshot = mutation.get();
              return new IdempotencyResponse(
                  200,
                  List.of(
                      new IdempotencyHeader("Content-Type", "application/json"),
                      new IdempotencyHeader("ETag", snapshot.etag())),
                  objectMapper.writeValueAsBytes(StoredBody.from(snapshot)));
            });
    if (response.status() != 200) {
      throw ProfileImageException.storageUnavailable();
    }
    StoredBody body = objectMapper.readValue(response.body(), StoredBody.class);
    ProfileImageSnapshot snapshot =
        new ProfileImageSnapshot(
            body.profileImageObjectKey(),
            body.profileImageUrl(),
            com.timingjeju.api.application.profile.ProfileImageSource.fromDatabase(
                body.profileImageSource()),
            body.profileImageVersion(),
            body.updatedAt());
    return new ProfileImageApplyResult(snapshot, !executed.get());
  }

  private record StoredBody(
      String profileImageObjectKey,
      String profileImageUrl,
      String profileImageSource,
      long profileImageVersion,
      Instant updatedAt) {

    private static StoredBody from(ProfileImageSnapshot snapshot) {
      return new StoredBody(
          snapshot.objectKey(),
          snapshot.imageUrl(),
          snapshot.source().wireValue(),
          snapshot.version(),
          snapshot.updatedAt());
    }
  }
}
