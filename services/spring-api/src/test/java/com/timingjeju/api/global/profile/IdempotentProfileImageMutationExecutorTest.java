package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyOperation;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.profile.ProfileImageApplyResult;
import com.timingjeju.api.application.profile.ProfileImageCommand;
import com.timingjeju.api.application.profile.ProfileImageSnapshot;
import com.timingjeju.api.application.profile.ProfileImageSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class IdempotentProfileImageMutationExecutorTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String KEY = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final String IDEMPOTENCY_KEY = "018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final ObjectMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
  private static final ProfileImageSnapshot SNAPSHOT =
      new ProfileImageSnapshot(
          KEY,
          "https://project.example.invalid/storage/v1/object/public/profile-images/" + KEY,
          ProfileImageSource.STORAGE,
          1,
          Instant.parse("2026-08-25T11:00:00Z"));

  @Test
  void completed_snapshot_replay는_mutation과_stale_CAS를_호출하지_않는다() {
    AtomicBoolean mutationCalled = new AtomicBoolean();
    IdempotencyUseCase replay =
        (request, operation) ->
            new IdempotencyResponse(
                200,
                List.of(
                    new IdempotencyHeader("Content-Type", "application/json"),
                    new IdempotencyHeader("ETag", SNAPSHOT.etag())),
                storedBody());

    ProfileImageApplyResult result =
        new IdempotentProfileImageMutationExecutor(replay, JSON)
            .execute(
                OWNER,
                IDEMPOTENCY_KEY,
                ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\""),
                () -> {
                  mutationCalled.set(true);
                  throw new AssertionError("replay must resolve before mutation/CAS");
                });

    assertThat(result.replayed()).isTrue();
    assertThat(result.snapshot()).isEqualTo(SNAPSHOT);
    assertThat(mutationCalled).isFalse();
  }

  @Test
  void first_execution은_exact_body_ETag_snapshot을_idempotency_registry에_전달한다() {
    RecordingIdempotency idempotency = new RecordingIdempotency();

    ProfileImageApplyResult result =
        new IdempotentProfileImageMutationExecutor(idempotency, JSON)
            .execute(
                OWNER,
                IDEMPOTENCY_KEY,
                ProfileImageCommand.create(OWNER, KEY, "\"profile-image-0\""),
                () -> SNAPSHOT);

    assertThat(result.replayed()).isFalse();
    assertThat(idempotency.request.ownerSub()).isEqualTo(OWNER);
    assertThat(idempotency.request.normalizedPath()).isEqualTo("/api/v1/me/profile-image");
    assertThat(idempotency.response.status()).isEqualTo(200);
    assertThat(idempotency.response.headers())
        .extracting(IdempotencyHeader::name, IdempotencyHeader::value)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Content-Type", "application/json"),
            org.assertj.core.groups.Tuple.tuple("ETag", "\"profile-image-1\""));
    assertThat(JSON.readTree(idempotency.response.body())).isEqualTo(JSON.readTree(storedBody()));
  }

  private static byte[] storedBody() {
    return JSON.writeValueAsBytes(
        java.util.Map.of(
            "profileImageObjectKey",
            SNAPSHOT.objectKey(),
            "profileImageUrl",
            SNAPSHOT.imageUrl(),
            "profileImageSource",
            SNAPSHOT.source().wireValue(),
            "profileImageVersion",
            SNAPSHOT.version(),
            "updatedAt",
            SNAPSHOT.updatedAt()));
  }

  private static final class RecordingIdempotency implements IdempotencyUseCase {
    private IdempotencyRequest request;
    private IdempotencyResponse response;

    @Override
    public IdempotencyResponse execute(IdempotencyRequest request, IdempotencyOperation operation) {
      this.request = request;
      response = operation.execute();
      return response;
    }
  }
}
