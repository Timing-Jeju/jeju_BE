package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  void confirmation은_공식_InfoRenderer_shape와_독립_HEAD_strong_ETag를_모두_확인한다() {
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
  @SuppressWarnings("unchecked")
  void pinned_공식_InfoRenderer_fixture를_compiled_parser가_해석한다() throws Exception {
    Path fixture =
        Path.of("..", "..", "fixtures", "contracts", "profile-legal", "supabase-storage-info.json")
            .toAbsolutePath()
            .normalize();
    Map<String, Object> document =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .readValue(Files.readAllBytes(fixture), Map.class);
    byte[] response =
        JsonMapper.builder()
            .findAndAddModules()
            .build()
            .writeValueAsBytes(document.get("response"));
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), response));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"head-etag\"")), new byte[0]));

    ProfileImageMetadata parsed = reader(transport).read(KEY).orElseThrow();

    assertThat(parsed.ownerId()).isEqualTo(OWNER);
    assertThat(parsed.storageEtag()).isEqualTo("\"head-etag\"");
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
  void 공식_info의_name이나_bucket이_expected_key와_다르면_객체_부재로_은닉한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(
        response(
            200,
            Map.of(),
            metadata(
                GENERATION,
                KEY.replace(OWNER.toString(), "19000000-0000-4000-8000-000000000001"))));

    assertCode(() -> reader(transport).read(KEY), "PROFILE_IMAGE_NOT_FOUND");
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET");
  }

  @Test
  void 공식_info의_top_level_ETag와_HEAD_ETag가_byte_exact하지_않으면_503이다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadataWithEtag(GENERATION, KEY, "info-etag")));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"head-etag\"")), new byte[0]));

    assertStorageUnavailable(() -> reader(transport).read(KEY));
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET", "HEAD");
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
    return metadata(generation, KEY);
  }

  private static byte[] metadata(String generation, String key) {
    return metadataWithEtag(generation, key, "head-etag");
  }

  private static byte[] metadataWithEtag(String generation, String key, String etag) {
    return ("""
            {
              "id":"79000000-0000-4000-8000-000000000001",
              "name":"%s",
              "version":"79000000-0000-4000-8000-000000000002",
              "bucket_id":"profile-images",
              "size":1024,
              "content_type":"image/webp",
              "etag":"\\\"%s\\\"",
              "metadata":{
                "generation":"%s"
              },
              "last_modified":"2026-08-25T11:00:00Z"
            }
            """
            .formatted(key, etag, generation))
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
