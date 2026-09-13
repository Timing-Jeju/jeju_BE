package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

public final class SupabaseProfileImageDeletionHttpGateway implements ProfileImageStorageGateway {
  private static final int MAXIMUM_BODY_BYTES = 1024 * 1024;
  private static final String PREFIX_MARKER = "profile-images/";
  private final SupabaseAdminSettings settings;
  private final ObjectMapper objectMapper;
  private final SupabaseAdminHttpTransport transport;

  public SupabaseProfileImageDeletionHttpGateway(
      SupabaseAdminSettings settings,
      ObjectMapper objectMapper,
      SupabaseAdminHttpTransport transport) {
    this.settings = java.util.Objects.requireNonNull(settings);
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    this.transport = java.util.Objects.requireNonNull(transport);
  }

  @Override
  public ExternalDeletionResult deletePrefix(String prefix) {
    String subject = subjectFrom(prefix);
    List<String> objects = listRecursively(subject);
    for (int from = 0; from < objects.size(); from += 1000) {
      int to = Math.min(from + 1000, objects.size());
      deleteBatch(objects.subList(from, to));
    }
    return objects.isEmpty()
        ? ExternalDeletionResult.ALREADY_ABSENT
        : ExternalDeletionResult.DELETED;
  }

  private List<String> listRecursively(String root) {
    ArrayDeque<String> directories = new ArrayDeque<>();
    Set<String> visited = new LinkedHashSet<>();
    List<String> objects = new ArrayList<>();
    directories.add(root);
    int pages = 0;
    while (!directories.isEmpty()) {
      String directory = directories.removeFirst();
      if (!visited.add(directory)) {
        throw DeletionOperationException.terminal("STORAGE_PAGINATION_LOOP_DETECTED");
      }
      int offset = 0;
      while (true) {
        if (++pages > settings.storageMaximumPages()) {
          throw DeletionOperationException.terminal("STORAGE_PAGINATION_LIMIT_EXCEEDED");
        }
        List<Map<?, ?>> rows = list(directory, offset);
        for (Map<?, ?> row : rows) {
          String name = safeName(row.get("name"));
          String key = directory + "/" + name;
          requireWithinRoot(root, key);
          if (row.get("id") == null) {
            directories.addLast(key);
          } else if (row.get("id") instanceof String id && !id.isBlank()) {
            objects.add(key);
          } else {
            throw DeletionOperationException.terminal("STORAGE_RESPONSE_INVALID");
          }
        }
        if (rows.size() < settings.storagePageSize()) {
          break;
        }
        offset += rows.size();
      }
    }
    return List.copyOf(objects);
  }

  @SuppressWarnings("unchecked")
  private List<Map<?, ?>> list(String prefix, int offset) {
    byte[] body =
        encode(
            Map.of(
                "prefix",
                prefix,
                "limit",
                settings.storagePageSize(),
                "offset",
                offset,
                "sortBy",
                Map.of("column", "name", "order", "asc")));
    SupabaseAdminHttpResponse response =
        exchange("POST", "/storage/v1/object/list/profile-images", body);
    if (response.status() == 404) {
      return List.of();
    }
    classify(response.status(), "STORAGE_LIST");
    try {
      Object decoded = objectMapper.readValue(response.body(), List.class);
      if (!(decoded instanceof List<?> list)) {
        throw new IllegalArgumentException();
      }
      List<Map<?, ?>> rows = new ArrayList<>();
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> row)) {
          throw new IllegalArgumentException();
        }
        rows.add(row);
      }
      return rows;
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("STORAGE_RESPONSE_INVALID");
    }
  }

  private void deleteBatch(List<String> keys) {
    SupabaseAdminHttpResponse response =
        exchange(
            "DELETE",
            "/storage/v1/object/profile-images",
            encode(Map.of("prefixes", List.copyOf(keys))));
    if (response.status() != 404) {
      classify(response.status(), "STORAGE_DELETE");
    }
  }

  private SupabaseAdminHttpResponse exchange(String method, String path, byte[] body) {
    HttpRequest request =
        HttpRequest.newBuilder(settings.baseUrl().resolve(path))
            .header("Authorization", "Bearer " + settings.serviceRoleKey())
            .header("apikey", settings.serviceRoleKey())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(settings.readTimeout())
            .method(method, BodyPublishers.ofByteArray(body))
            .build();
    return transport.exchange(request, body, MAXIMUM_BODY_BYTES);
  }

  private static void classify(int status, String operation) {
    if (status >= 200 && status < 300) {
      return;
    }
    if (status == 429 || status >= 500) {
      throw DeletionOperationException.retryable(operation + "_UNAVAILABLE");
    }
    throw DeletionOperationException.terminal(operation + "_REJECTED");
  }

  private byte[] encode(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("STORAGE_REQUEST_INVALID");
    }
  }

  private static String subjectFrom(String prefix) {
    try {
      if (prefix == null || !prefix.startsWith(PREFIX_MARKER)) {
        throw new IllegalArgumentException();
      }
      String value = prefix.substring(PREFIX_MARKER.length());
      if (value.contains("/")
          || !SupabaseAuthAdminHttpGateway.canonicalSubject(AuthSubject.of(value)).equals(value)) {
        throw new IllegalArgumentException();
      }
      return value;
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("INVALID_AUTH_SUBJECT");
    }
  }

  private static String safeName(Object raw) {
    if (!(raw instanceof String name)
        || name.isBlank()
        || name.equals(".")
        || name.equals("..")
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl)) {
      throw DeletionOperationException.terminal("STORAGE_RESPONSE_INVALID");
    }
    return name;
  }

  private static void requireWithinRoot(String root, String key) {
    if (!key.startsWith(root + "/") || key.contains("/../") || key.contains("/./")) {
      throw DeletionOperationException.terminal("STORAGE_RESPONSE_INVALID");
    }
  }
}
