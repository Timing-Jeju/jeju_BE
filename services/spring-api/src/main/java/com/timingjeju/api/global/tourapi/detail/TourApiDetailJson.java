package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.PlaceDetailImportException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class TourApiDetailJson {
  private static final int MAX_TEXT_BYTES = 65_536;
  private final ObjectReader reader;

  TourApiDetailJson(ObjectMapper objectMapper) {
    reader =
        Objects.requireNonNull(objectMapper)
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  JsonNode item(SnapshotPayloadFormat format, byte[] payload) {
    if (format != SnapshotPayloadFormat.JSON || payload == null) {
      throw PlaceDetailImportException.invalidResponse();
    }
    try {
      JsonNode response = reader.readTree(payload).path("response");
      if (!response.path("header").path("resultCode").isTextual()
          || !"0000".equals(response.path("header").path("resultCode").asString())) {
        throw PlaceDetailImportException.invalidResponse();
      }
      JsonNode items = response.path("body").path("items").path("item");
      if (!items.isArray() || items.size() != 1 || !items.get(0).isObject()) {
        throw PlaceDetailImportException.invalidResponse();
      }
      return items.get(0);
    } catch (PlaceDetailImportException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw PlaceDetailImportException.invalidResponse();
    }
  }

  static String requiredText(JsonNode item, String field) {
    String value = optionalText(item, field);
    if (value == null) {
      throw PlaceDetailImportException.invalidResponse();
    }
    return value;
  }

  static String optionalText(JsonNode item, String field) {
    if (!item.has(field)) {
      return null;
    }
    JsonNode value = item.path(field);
    if (!value.isTextual()) {
      throw PlaceDetailImportException.invalidResponse();
    }
    String text = value.asString().strip();
    if (text.isEmpty()) {
      return null;
    }
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
      throw PlaceDetailImportException.invalidResponse();
    }
    return text;
  }
}
