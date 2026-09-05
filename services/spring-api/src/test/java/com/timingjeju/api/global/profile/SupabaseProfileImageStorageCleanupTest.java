package com.timingjeju.api.global.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SupabaseProfileImageStorageCleanupTest {

  private static final UUID OWNER = UUID.fromString("09000000-0000-4000-8000-000000000001");
  private static final String KEY = OWNER + "/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675";
  private static final ProfileImageMetadata EXPECTED =
      new ProfileImageMetadata(
          KEY, OWNER, "image/webp", 1024, "\"etag-1\"", Instant.parse("2026-09-01T00:00:00Z"));

  @Test
  void delete는_info_owner_generation과_current_HEAD_ETag를_독립_재검증한_뒤에만_호출한다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata()));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-1\"")), new byte[0]));
    transport.enqueue(response(200, Map.of(), deletedObject(KEY)));

    gateway(transport).deleteExact(EXPECTED);

    assertThat(transport.requests)
        .extracting(HttpRequest::method)
        .containsExactly("GET", "HEAD", "DELETE");
    HttpRequest delete = transport.requests.get(2);
    assertThat(delete.uri().getPath()).isEqualTo("/storage/v1/object/profile-images");
    assertThat(delete.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(new String(transport.requestBodies.get(2), StandardCharsets.UTF_8))
        .isEqualTo("{\"prefixes\":[\"" + KEY + "\"]}");
  }

  @Test
  void delete의_owner_key_generation_ETag_mismatch는_DELETE없이_503이다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata()));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-2\"")), new byte[0]));

    assertThatThrownBy(() -> gateway(transport).deleteExact(EXPECTED))
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("PROFILE_IMAGE_STORAGE_UNAVAILABLE");
    assertThat(transport.requests).extracting(HttpRequest::method).containsExactly("GET", "HEAD");
  }

  @Test
  void exact_fence후_bucket_endpoint_DELETE_404는_missing_bucket이므로_503이다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata()));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-1\"")), new byte[0]));
    transport.enqueue(response(404, Map.of(), new byte[0]));

    assertStorageUnavailable(() -> gateway(transport).deleteExact(EXPECTED));
  }

  @Test
  void DELETE_200_empty_array는_missing_object의_idempotent_success이다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata()));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-1\"")), new byte[0]));
    transport.enqueue(response(200, Map.of(), "[]".getBytes(StandardCharsets.UTF_8)));

    gateway(transport).deleteExact(EXPECTED);

    assertThat(transport.requests)
        .extracting(HttpRequest::method)
        .containsExactly("GET", "HEAD", "DELETE");
  }

  @Test
  void DELETE_200은_empty_or_exact_deleted_object외_response를_503으로_fail_closed한다() {
    for (byte[] responseBody :
        List.of(
            deletedObject(OWNER + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675"),
            "{}".getBytes(StandardCharsets.UTF_8))) {
      RecordingTransport transport = new RecordingTransport();
      transport.enqueue(response(200, Map.of(), metadata()));
      transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-1\"")), new byte[0]));
      transport.enqueue(response(200, Map.of(), responseBody));

      assertStorageUnavailable(() -> gateway(transport).deleteExact(EXPECTED));
    }
  }

  @Test
  void DELETE는_documented_200_response외_status를_503으로_닫는다() {
    RecordingTransport transport = new RecordingTransport();
    transport.enqueue(response(200, Map.of(), metadata()));
    transport.enqueue(response(200, Map.of("ETag", List.of("\"etag-1\"")), new byte[0]));
    transport.enqueue(response(204, Map.of(), new byte[0]));

    assertStorageUnavailable(() -> gateway(transport).deleteExact(EXPECTED));
  }

  @Test
  void orphan_list는_folder_only_entry를_재귀_page하고_global_order를_중복누락없이_복원한다() {
    RecordingTransport transport = new RecordingTransport();
    enqueueRecursiveCatalog(transport);
    enqueueRecursiveCatalog(transport);

    var first = gateway(transport).list(0, 2);
    var second = gateway(transport).list(2, 2);

    assertThat(first.objectKeys())
        .containsExactly(KEY, OWNER + "/profile/118f47a1-43d2-7b6e-9fa2-11a1cc32c675");
    assertThat(first.hasMore()).isTrue();
    assertThat(second.objectKeys())
        .containsExactly(
            OWNER + "/profile/218f47a1-43d2-7b6e-9fa2-11a1cc32c675",
            "19000000-0000-4000-8000-000000000001/profile/318f47a1-43d2-7b6e-9fa2-11a1cc32c675");
    assertThat(second.hasMore()).isFalse();
    List<String> allKeys = new ArrayList<>(first.objectKeys());
    allKeys.addAll(second.objectKeys());
    assertThat(allKeys).hasSize(4).doesNotHaveDuplicates().isSorted();
    assertThat(transport.requests).allMatch(request -> request.method().equals("POST"));
    assertListRequest(transport.requestBodies.get(0), "", 0, 2);
    assertListRequest(transport.requestBodies.get(2), OWNER.toString(), 0, 2);
    assertListRequest(transport.requestBodies.get(3), OWNER + "/profile", 0, 2);
    assertListRequest(transport.requestBodies.get(4), OWNER + "/profile", 2, 2);
  }

  private static void enqueueRecursiveCatalog(RecordingTransport transport) {
    String otherOwner = "19000000-0000-4000-8000-000000000001";
    enqueueJson(
        transport,
        "[{\"name\":\"" + OWNER + "\",\"id\":null},{\"name\":\"" + otherOwner + "\",\"id\":null}]");
    enqueueJson(transport, "[]");
    enqueueJson(transport, "[{\"name\":\"profile\",\"id\":null}]");
    enqueueJson(
        transport,
        "[{\"name\":\"018f47a1-43d2-7b6e-9fa2-11a1cc32c675\",\"id\":\"object-1\"},{\"name\":\"118f47a1-43d2-7b6e-9fa2-11a1cc32c675\",\"id\":\"object-2\"}]");
    enqueueJson(
        transport, "[{\"name\":\"218f47a1-43d2-7b6e-9fa2-11a1cc32c675\",\"id\":\"object-3\"}]");
    enqueueJson(transport, "[{\"name\":\"profile\",\"id\":null}]");
    enqueueJson(
        transport, "[{\"name\":\"318f47a1-43d2-7b6e-9fa2-11a1cc32c675\",\"id\":\"object-4\"}]");
  }

  private static void enqueueJson(RecordingTransport transport, String json) {
    transport.enqueue(response(200, Map.of(), json.getBytes(StandardCharsets.UTF_8)));
  }

  private static void assertListRequest(byte[] body, String prefix, int offset, int limit) {
    assertThat(new String(body, StandardCharsets.UTF_8))
        .contains(
            "\"prefix\":\"" + prefix + "\"",
            "\"limit\":" + limit,
            "\"offset\":" + offset,
            "\"column\":\"name\"",
            "\"order\":\"asc\"");
  }

  private static void assertStorageUnavailable(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(ProfileImageException.class)
        .extracting(failure -> ((ProfileImageException) failure).code())
        .isEqualTo("PROFILE_IMAGE_STORAGE_UNAVAILABLE");
  }

  private static SupabaseProfileImageMetadataReader gateway(RecordingTransport transport) {
    return new SupabaseProfileImageMetadataReader(
        ProfileImageStorageSettings.enabled(
            URI.create("https://project.example.invalid"),
            "unit-key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        JsonMapper.builder().findAndAddModules().build(),
        transport);
  }

  private static ProfileImageStorageHttpResponse response(
      int status, Map<String, List<String>> headers, byte[] body) {
    return new ProfileImageStorageHttpResponse(status, headers, body);
  }

  private static byte[] metadata() {
    return ("""
            {
              "name":"%s",
              "bucket_id":"profile-images",
              "owner_id":"%s",
              "updated_at":"2026-09-01T00:00:00Z",
              "user_metadata":{
                "generation":"018f47a1-43d2-7b6e-9fa2-11a1cc32c675"
              },
              "metadata":{
                "mimetype":"image/webp",
                "size":1024
              }
            }
            """
            .formatted(KEY, OWNER))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] deletedObject(String key) {
    return ("[{\"name\":\"%s\",\"bucket_id\":\"profile-images\"}]".formatted(key))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static final class RecordingTransport implements ProfileImageStorageHttpTransport {
    private final Queue<ProfileImageStorageHttpResponse> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<byte[]> requestBodies = new ArrayList<>();

    void enqueue(ProfileImageStorageHttpResponse response) {
      responses.add(response);
    }

    @Override
    public ProfileImageStorageHttpResponse exchange(
        HttpRequest request, byte[] requestBody, int maximumBodyBytes) {
      requests.add(request);
      requestBodies.add(requestBody);
      return responses.remove();
    }
  }
}
