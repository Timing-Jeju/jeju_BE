package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageException;
import com.timingjeju.api.application.profile.ProfileImageMetadata;
import com.timingjeju.api.application.profile.ProfileImageObjectPage;
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
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

final class SupabaseProfileImageMetadataReader
    implements ProfileImageStorageMetadataReader,
        ProfileImageStorageCatalog,
        ProfileImageStorageObjectDeleter {

  private static final int MAXIMUM_INFO_BYTES = 64 * 1024;
  private static final int MAXIMUM_LIST_BYTES = 256 * 1024;
  private static final int MAXIMUM_CATALOG_PAGES = 1_000;
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
    return Optional.of(
        new ProfileImageMetadata(
            metadata.objectKey(),
            metadata.ownerId(),
            metadata.contentType(),
            metadata.sizeBytes(),
            etag.orElseThrow(),
            metadata.updatedAt()));
  }

  @Override
  public ProfileImageObjectPage list(int offset, int limit) {
    requireEnabled();
    if (offset < 0 || limit < 1 || limit > 100) {
      throw ProfileImageException.storageUnavailable();
    }
    List<String> keys = new ArrayList<>();
    int[] pages = {0};
    listDirectory("", limit, keys, pages);
    keys.sort(String::compareTo);
    if (offset >= keys.size()) {
      return new ProfileImageObjectPage(List.of(), false);
    }
    int end = Math.min(keys.size(), Math.addExact(offset, limit));
    return new ProfileImageObjectPage(keys.subList(offset, end), end < keys.size());
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
  private void listDirectory(String prefix, int limit, List<String> keys, int[] pages) {
    List<String> folders = new ArrayList<>();
    int directoryOffset = 0;
    while (true) {
      if (++pages[0] > MAXIMUM_CATALOG_PAGES) {
        throw ProfileImageException.storageUnavailable();
      }
      byte[] body =
          objectMapper.writeValueAsBytes(
              Map.of(
                  "prefix", prefix,
                  "limit", limit,
                  "offset", directoryOffset,
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
      for (Object row : rows) {
        if (!(row instanceof Map<?, ?> item)) {
          throw ProfileImageException.storageUnavailable();
        }
        String name = string(item, "name");
        String fullName = prefix.isEmpty() ? name : prefix + "/" + name;
        Object id = item.get("id");
        if (id == null) {
          requireCatalogFolder(fullName);
          folders.add(fullName);
        } else if (id instanceof String objectId && !objectId.isBlank()) {
          requireCanonicalObjectKey(fullName);
          keys.add(fullName);
        } else {
          throw ProfileImageException.storageUnavailable();
        }
      }
      if (rows.size() < limit) {
        break;
      }
      directoryOffset = Math.addExact(directoryOffset, limit);
    }
    for (String folder : folders) {
      listDirectory(folder, limit, keys, pages);
    }
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
    if (!(rawMetadata instanceof Map<?, ?> metadata)) {
      throw ProfileImageException.storageUnavailable();
    }
    Object rawUserMetadata = root.get("user_metadata");
    if (!(rawUserMetadata instanceof Map<?, ?> userMetadata)) {
      throw ProfileImageException.storageUnavailable();
    }
    String key = string(root, "name");
    if (!expectedKey.equals(key) || !"profile-images".equals(string(root, "bucket_id"))) {
      throw ProfileImageException.notFound();
    }
    String ownerValue = string(root, "owner_id");
    UUID owner = UUID.fromString(ownerValue);
    if (!owner.toString().equals(ownerValue)) {
      throw ProfileImageException.storageUnavailable();
    }
    if (!expectedKey.startsWith(ownerValue + "/profile/")) {
      throw ProfileImageException.notFound();
    }
    String generation = string(userMetadata, "generation");
    String expectedGeneration = expectedKey.substring(expectedKey.lastIndexOf('/') + 1);
    if (!UUID.fromString(generation).toString().equals(generation)
        || !expectedGeneration.equals(generation)) {
      throw ProfileImageException.storageUnavailable();
    }
    String mime = string(metadata, "mimetype");
    long size = number(metadata, "size").longValueExact();
    Instant updatedAt = Instant.parse(string(root, "updated_at"));
    return new ProfileImageMetadata(key, owner, mime, size, null, updatedAt);
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
