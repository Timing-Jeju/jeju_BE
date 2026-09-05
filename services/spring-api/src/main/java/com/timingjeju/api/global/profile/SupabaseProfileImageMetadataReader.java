package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageObjectPage;
import com.timingjeju.api.application.profile.ProfileImageOwnershipProof;
import com.timingjeju.api.application.profile.ProfileImageScanCursor;
import com.timingjeju.api.application.profile.ProfileImageStorageCatalog;
import com.timingjeju.api.application.profile.ProfileImageStorageMetadataReader;
import com.timingjeju.api.application.profile.ProfileImageStorageObjectDeleter;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

final class SupabaseProfileImageMetadataReader
    implements ProfileImageStorageMetadataReader,
        ProfileImageStorageCatalog,
        ProfileImageStorageObjectDeleter {

  private static final int MAXIMUM_INFO_BYTES = 64 * 1024;
  private static final int MAXIMUM_LIST_BYTES = 256 * 1024;
  private static final Pattern STRONG_ETAG = Pattern.compile("^\\\"[^\\\"\\p{Cntrl}]+\\\"$");
  private static final Pattern OBJECT_KEY =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/profile/"
              + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final ProfileImageStorageSettings settings;
  private final ObjectMapper objectMapper;
  private final ProfileImageStorageHttpTransport transport;

  SupabaseProfileImageMetadataReader(
      ProfileImageStorageSettings settings,
      ObjectMapper objectMapper,
      ProfileImageStorageHttpTransport transport) {
    this.settings = java.util.Objects.requireNonNull(settings);
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    this.transport = java.util.Objects.requireNonNull(transport);
  }

  @Override
  public Optional<ProfileImageMetadata> read(String objectKey) {
    requireEnabled();
    requireCanonicalObjectKey(objectKey);
    ProfileImageStorageHttpResponse info =
        exchange(
            "GET",
            "/storage/v1/object/info/profile-images/" + objectKey,
            new byte[0],
            MAXIMUM_INFO_BYTES);
    if (info.status() == 404) {
      return Optional.empty();
    }
    if (info.status() != 200) {
      throw ProfileImageException.storageUnavailable();
    }
    ProfileImageMetadata metadata = parseInfo(objectKey, info.body());
    Optional<String> etag = head(objectKey);
    if (etag.isEmpty()) {
      return Optional.empty();
    }
    if (!metadata.storageEtag().equals(etag.orElseThrow())) {
      throw ProfileImageException.storageUnavailable();
    }
    return Optional.of(
        new ProfileImageMetadata(
            metadata.objectKey(),
            metadata.ownerId(),
            metadata.contentType(),
            metadata.sizeBytes(),
            metadata.storageEtag(),
            metadata.updatedAt()));
  }

  @Override
  public ProfileImageObjectPage list(ProfileImageScanCursor cursor, int limit) {
    requireEnabled();
    java.util.Objects.requireNonNull(cursor);
    if (limit < 1 || limit > 100) {
      throw ProfileImageException.storageUnavailable();
    }
    List<Map<?, ?>> owners = listDirectory("", cursor.ownerOffset(), 1);
    if (owners.isEmpty()) {
      return new ProfileImageObjectPage(List.of(), cursor.wrap(), true);
    }
    String owner = requireFolder(owners.getFirst(), "");
    List<Map<?, ?>> profiles = listDirectory(owner, 0, 1);
    if (profiles.size() != 1 || !"profile".equals(requireFolder(profiles.getFirst(), owner))) {
      return new ProfileImageObjectPage(List.of(), cursor.nextOwner(), false);
    }
    String prefix = owner + "/profile";
    List<Map<?, ?>> objects = listDirectory(prefix, cursor.objectOffset(), limit);
    List<String> keys = objects.stream().map(row -> requireObject(row, prefix)).sorted().toList();
    ProfileImageScanCursor next =
        objects.size() == limit ? cursor.nextObjects(objects.size()) : cursor.nextOwner();
    return new ProfileImageObjectPage(keys, next, false);
  }

  @Override
  public void deleteExact(ProfileImageMetadata expected) {
    requireEnabled();
    java.util.Objects.requireNonNull(expected);
    ProfileImageMetadata current = read(expected.objectKey()).orElse(null);
    if (current == null) {
      return;
    }
    if (!current.equals(expected)) {
      throw ProfileImageException.storageUnavailable();
    }
    byte[] body = objectMapper.writeValueAsBytes(Map.of("prefixes", List.of(expected.objectKey())));
    ProfileImageStorageHttpResponse response =
        exchange("DELETE", "/storage/v1/object/profile-images", body, MAXIMUM_INFO_BYTES);
    if (response.status() != 200 || !isExactDeleteResponse(expected.objectKey(), response.body())) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @SuppressWarnings("unchecked")
  private List<Map<?, ?>> listDirectory(String prefix, int offset, int limit) {
    byte[] body =
        objectMapper.writeValueAsBytes(
            Map.of(
                "prefix", prefix,
                "limit", limit,
                "offset", offset,
                "sortBy", Map.of("column", "name", "order", "asc")));
    ProfileImageStorageHttpResponse response =
        exchange("POST", "/storage/v1/object/list/profile-images", body, MAXIMUM_LIST_BYTES);
    if (response.status() != 200) {
      throw ProfileImageException.storageUnavailable();
    }
    Object decoded = objectMapper.readValue(response.body(), List.class);
    if (!(decoded instanceof List<?> rows)) {
      throw ProfileImageException.storageUnavailable();
    }
    List<Map<?, ?>> result = new ArrayList<>(rows.size());
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> item)) {
        throw ProfileImageException.storageUnavailable();
      }
      result.add(item);
    }
    return List.copyOf(result);
  }

  private static String requireFolder(Map<?, ?> item, String prefix) {
    if (item.get("id") != null) {
      throw ProfileImageException.storageUnavailable();
    }
    String name = string(item, "name");
    String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
    requireCatalogFolder(fullName);
    return name;
  }

  private static String requireObject(Map<?, ?> item, String prefix) {
    if (!(item.get("id") instanceof String id) || id.isBlank()) {
      throw ProfileImageException.storageUnavailable();
    }
    String fullName = prefix + "/" + string(item, "name");
    requireCanonicalObjectKey(fullName);
    return fullName;
  }

  @SuppressWarnings("unchecked")
  private boolean isExactDeleteResponse(String expectedKey, byte[] body) {
    try {
      Object decoded = objectMapper.readValue(body, List.class);
      if (!(decoded instanceof List<?> rows)) {
        return false;
      }
      if (rows.isEmpty()) {
        return true;
      }
      if (rows.size() != 1 || !(rows.getFirst() instanceof Map<?, ?> item)) {
        return false;
      }
      return expectedKey.equals(string(item, "name"))
          && "profile-images".equals(string(item, "bucket_id"));
    } catch (RuntimeException failure) {
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  ProfileImageMetadata parseInfo(String expectedKey, byte[] body) {
    try {
      return parseInfoUnchecked(expectedKey, body);
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  @SuppressWarnings("unchecked")
  private ProfileImageMetadata parseInfoUnchecked(String expectedKey, byte[] body) {
    Object decoded = objectMapper.readValue(body, Map.class);
    if (!(decoded instanceof Map<?, ?> root)) {
      throw ProfileImageException.storageUnavailable();
    }
    Object rawMetadata = root.get("metadata");
    if (!(rawMetadata instanceof Map<?, ?> userMetadata)) {
      throw ProfileImageException.storageUnavailable();
    }
    ProfileImageOwnershipProof proof = ProfileImageOwnershipProof.fromCanonicalKey(expectedKey);
    String key = string(root, "name");
    if (!expectedKey.equals(key) || !"profile-images".equals(string(root, "bucket_id"))) {
      throw ProfileImageException.notFound();
    }
    requireOpaqueInfoField(root, "id");
    requireOpaqueInfoField(root, "version");
    String generation = string(userMetadata, "generation");
    if (!proof.generation().equals(generation)) {
      throw ProfileImageException.storageUnavailable();
    }
    String mime = string(root, "content_type");
    long size = number(root, "size").longValueExact();
    String etag = string(root, "etag");
    if (!STRONG_ETAG.matcher(etag).matches()) {
      throw ProfileImageException.storageUnavailable();
    }
    Instant updatedAt = Instant.parse(string(root, "last_modified"));
    return new ProfileImageMetadata(key, proof.ownerId(), mime, size, etag, updatedAt);
  }

  private static void requireOpaqueInfoField(Map<?, ?> root, String key) {
    String value = string(root, key);
    if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private Optional<String> head(String objectKey) {
    ProfileImageStorageHttpResponse response =
        exchange("HEAD", "/storage/v1/object/profile-images/" + objectKey, new byte[0], 0);
    if (response.status() == 404) {
      return Optional.empty();
    }
    if (response.status() != 200) {
      throw ProfileImageException.storageUnavailable();
    }
    String etag =
        response.singleHeader("ETag").orElseThrow(ProfileImageException::storageUnavailable);
    if (!STRONG_ETAG.matcher(etag).matches()) {
      throw ProfileImageException.storageUnavailable();
    }
    return Optional.of(etag);
  }

  private ProfileImageStorageHttpResponse exchange(
      String method, String path, byte[] body, int maximumBodyBytes) {
    try {
      URI uri = URI.create(baseUrl() + path);
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri)
              .timeout(settings.readTimeout())
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + settings.serviceRoleKey())
              .header("apikey", settings.serviceRoleKey());
      if (body.length > 0) {
        request
            .header("Content-Type", "application/json")
            .method(method, BodyPublishers.ofByteArray(body));
      } else {
        request.method(method, BodyPublishers.noBody());
      }
      return transport.exchange(request.build(), body, maximumBodyBytes);
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (Exception failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private String baseUrl() {
    return settings.baseUrl().toString().replaceAll("/+$", "");
  }

  private void requireEnabled() {
    if (!settings.enabled()) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private static void requireCanonicalObjectKey(String objectKey) {
    if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches()) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private static void requireCatalogFolder(String folder) {
    if (!folder.matches(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(/profile)?$")) {
      throw ProfileImageException.storageUnavailable();
    }
  }

  private static String string(Map<?, ?> source, String key) {
    Object value = source.get(key);
    if (!(value instanceof String string) || string.isBlank()) {
      throw ProfileImageException.storageUnavailable();
    }
    return string;
  }

  private static java.math.BigDecimal number(Map<?, ?> source, String key) {
    Object value = source.get(key);
    if (value instanceof java.math.BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Number number) {
      return new java.math.BigDecimal(number.toString());
    }
    throw ProfileImageException.storageUnavailable();
  }
}
