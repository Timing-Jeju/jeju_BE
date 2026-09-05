package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SupabaseProfileImageMetadataReaderTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String GENERATION = "018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final String KEY = OWNER + "/profile/" + GENERATION;

  @Test
  void confirmation은_info_metadata와_독립_HEAD_strong_ETag를_모두_확인한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata(GENERATION)));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"head-etag\"")), new byte[0]));

    ProfileImageMetadata result = reader(transport).read(KEY).orElseThrow();

    assertThat(result.storageEtag()).isEqualTo("\"head-etag\"");
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET", "HEAD");
    assertThat(transport.requests.get(0).uri().getPath())
        .isEqualTo("/storage/v1/object/info/profile-images/" + KEY);
    assertThat(transport.requests.get(1).uri().getPath())
        .isEqualTo("/storage/v1/object/profile-images/" + KEY);
  }

  @Test
  void HEAD_404는_객체_부재로_은닉하고_missing_weak_redirect는_503으로_fail_closed한다() {
    RecordingTransport missing = new RecordingTransport();
    missing.enqueue(response(200, Map.of(), metadata(GENERATION)));
    missing.enqueue(response(404, Map.of(), new byte[0]));
    assertThat(reader(missing).read(KEY)).isEmpty();

    for (ProfileImageStorageHttpResponse head :
        List.of(
            response(200, Map.of(), new byte[0]),
            response(200, Map.of("ETag", List.of("W/\"weak\"")), new byte[0]),
            response(302, Map.of("ETag", List.of("\"redirect\"")), new byte[0]))) {
      RecordingTransport malformed = new RecordingTransport();
      malformed.enqueue(response(200, Map.of(), metadata(GENERATION)));
      malformed.enqueue(head);
      assertStorageUnavailable(() -> reader(malformed).read(KEY));
    }
  }

  @Test
  void metadata_generation은_object_key_generation과_exact_match해야_한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata("118f47a1-43d2-7b6e-9fa2-11a1cc32c675")));

    assertStorageUnavailable(() -> reader(transport).read(KEY));
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET");
  }

  @Test
  void canonical_metadata_owner가_object_key_owner와_다르면_객체_부재_404로_은닉한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(
        response(200, Map.of(), metadata(GENERATION, "19000000-0000-4000-8000-000000000001")));

    assertCode(() -> reader(transport).read(KEY), "PROFILE_IMAGE_NOT_FOUND");
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET");
  }

  @Test
  void malformed_metadata_owner는_503으로_fail_closed한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(
        response(200, Map.of(), metadata(GENERATION, "09000000-0000-4000-8000-00000000000A")));

    assertStorageUnavailable(() -> reader(transport).read(KEY));
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET");
  }

  @Test
  void noncanonical_object_key는_Storage_request전에_fail_closed한다() {
    RecordingTransport transport = new RecordingTransport();

    assertStorageUnavailable(() -> reader(transport).read(KEY + "/extra"));

    assertThat(transport.requests).isEmpty();
  }

  private static SupabaseProfileImageMetadataReader reader(RecordingTransport transport) {
    return new SupabaseProfileImageMetadataReader(
        settings(), JsonMapper.builder().findAndAddModules().build(), transport);
  }

  private static ProfileImageStorageSettings settings() {
    return ProfileImageStorageSettings.enabled(
        URI.create("https://project.example.invalid"),
        "unit-key",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1));
  }

  private static ProfileImageStorageHttpResponse response(
      int status, Map<String, List<String>> headers, byte[] body) {
    return new ProfileImageStorageHttpResponse(status, headers, body);
  }

  private static void assertStorageUnavailable(Runnable operation) {
    assertCode(operation, "PROFILE_IMAGE_STORAGE_UNAVAILABLE");
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo(code);
  }

  private static byte[] metadata(String generation) {
    return metadata(generation, OWNER.toString());
  }

  private static byte[] metadata(String generation, String owner) {
    return ("""
            {
              "name":"%s",
              "bucket_id":"profile-images",
              "owner_id":"%s",
              "updated_at":"2026-08-25T11:00:00Z",
              "user_metadata":{
                "generation":"%s"
              },
              "metadata":{
                "mimetype":"image/webp",
                "size":1024
              }
            }
            """
            .formatted(KEY, owner, generation))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static final class RecordingTransport implements ProfileImageStorageHttpTransport {
    private final Queue<ProfileImageStorageHttpResponse> responses = new ArrayDeque<>();
    private final java.util.ArrayList<HttpRequest> requests = new java.util.ArrayList<>();

    void enqueue(ProfileImageStorageHttpResponse response) {
      responses.add(response);
    }

    @Override
    public ProfileImageStorageHttpResponse exchange(
        HttpRequest request, byte[] requestBody, int maximumBodyBytes) {
      requests.add(request);
      return Optional.ofNullable(responses.poll()).orElseThrow();
    }
  }
}
