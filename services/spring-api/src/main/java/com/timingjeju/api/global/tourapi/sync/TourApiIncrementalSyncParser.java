package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncException;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncPage;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncParser;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncAction;
import com.timingjeju.api.application.tourapi.sync.PlaceSyncChange;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TourApiIncrementalSyncParser implements IncrementalSyncParser {
  private static final DateTimeFormatter MODIFIED_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final ZoneId JEJU_ZONE = ZoneId.of("Asia/Seoul");
  private static final double MIN_LONGITUDE = 126.0;
  private static final double MAX_LONGITUDE = 127.0;
  private static final double MIN_LATITUDE = 33.0;
  private static final double MAX_LATITUDE = 34.0;
  private final ObjectReader reader;

  public TourApiIncrementalSyncParser(ObjectMapper objectMapper) {
    reader =
        Objects.requireNonNull(objectMapper)
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public IncrementalSyncPage parse(SnapshotPayloadFormat format, byte[] payload) {
    Objects.requireNonNull(format, "format은 필수입니다.");
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    if (format != SnapshotPayloadFormat.JSON) {
      throw IncrementalSyncException.invalidResponse();
    }
    try {
      JsonNode response = reader.readTree(payload).path("response");
      if (!"0000".equals(text(response.path("header"), "resultCode", 16))) {
        throw IncrementalSyncException.invalidResponse();
      }
      JsonNode body = response.path("body");
      int pageNo = positiveInt(body.path("pageNo"));
      int numOfRows = positiveInt(body.path("numOfRows"));
      int totalCount = nonNegativeInt(body.path("totalCount"));
      JsonNode itemsNode = body.path("items");
      if (!body.isObject() || itemsNode.isMissingNode()) {
        throw IncrementalSyncException.invalidResponse();
      }
      if ((itemsNode.isNull() || (itemsNode.isTextual() && itemsNode.asString().isBlank()))
          && totalCount == 0) {
        return new IncrementalSyncPage(pageNo, numOfRows, 0, 0, List.of());
      }
      JsonNode items = itemsNode.path("item");
      if (!items.isArray()) {
        throw IncrementalSyncException.invalidResponse();
      }
      List<PlaceSyncChange> changes = new ArrayList<>();
      Set<String> contentIds = new HashSet<>();
      for (JsonNode item : items) {
        PlaceSyncChange change = parseItem(item);
        if (!contentIds.add(change.contentId())) {
          throw IncrementalSyncException.invalidResponse();
        }
        changes.add(change);
      }
      return new IncrementalSyncPage(pageNo, numOfRows, totalCount, items.size(), changes);
    } catch (IncrementalSyncException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw IncrementalSyncException.invalidResponse();
    }
  }

  private static PlaceSyncChange parseItem(JsonNode item) {
    if (!item.isObject()) {
      throw IncrementalSyncException.invalidResponse();
    }
    String contentId = text(item, "contentid", 512);
    String contentTypeId = text(item, "contenttypeid", 128);
    Instant modifiedAt = modifiedAt(text(item, "modifiedtime", 32));
    String showFlag = text(item, "showflag", 1);
    if (contentId == null || contentTypeId == null || modifiedAt == null || showFlag == null) {
      throw IncrementalSyncException.invalidResponse();
    }
    if ("0".equals(showFlag)) {
      return new PlaceSyncChange(
          contentId, contentTypeId, modifiedAt, PlaceSyncAction.DELETE, null);
    }
    if (!"1".equals(showFlag)) {
      throw IncrementalSyncException.invalidResponse();
    }
    String title = text(item, "title", 1024);
    Double longitude = coordinate(item, "mapx");
    Double latitude = coordinate(item, "mapy");
    String region = optionalText(item, "lDongRegnCd", 128);
    if (title == null
        || containsHtml(title)
        || longitude == null
        || latitude == null
        || longitude < MIN_LONGITUDE
        || longitude > MAX_LONGITUDE
        || latitude < MIN_LATITUDE
        || latitude > MAX_LATITUDE
        || (region != null && !"50".equals(region))) {
      throw IncrementalSyncException.invalidResponse();
    }
    TourPlace place =
        new TourPlace(
            contentId,
            contentTypeId,
            title,
            longitude,
            latitude,
            optionalText(item, "addr1", 1024),
            optionalText(item, "addr2", 1024),
            optionalText(item, "firstimage", 8192),
            optionalText(item, "firstimage2", 8192),
            region,
            optionalText(item, "lDongSignguCd", 128),
            optionalText(item, "lclsSystm1", 128),
            optionalText(item, "lclsSystm2", 128),
            optionalText(item, "lclsSystm3", 128),
            modifiedAt);
    return new PlaceSyncChange(contentId, contentTypeId, modifiedAt, PlaceSyncAction.UPSERT, place);
  }

  private static String text(JsonNode node, String field, int maxBytes) {
    JsonNode value = node.path(field);
    if (!value.isTextual()) return null;
    String normalized = value.asString().strip();
    return normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes
        ? null
        : normalized;
  }

  private static String optionalText(JsonNode node, String field, int maxBytes) {
    return node.has(field) ? text(node, field, maxBytes) : null;
  }

  private static Double coordinate(JsonNode node, String field) {
    String value = text(node, field, 64);
    if (value == null) return null;
    try {
      double parsed = Double.parseDouble(value);
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Instant modifiedAt(String value) {
    if (value == null) return null;
    try {
      return LocalDateTime.parse(value, MODIFIED_FORMAT).atZone(JEJU_ZONE).toInstant();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static boolean containsHtml(String value) {
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    return value.indexOf('<') >= 0
        || value.indexOf('>') >= 0
        || normalized.contains("&lt;")
        || normalized.contains("&gt;")
        || normalized.contains("&#60;")
        || normalized.contains("&#62;");
  }

  private static int positiveInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 1) throw IncrementalSyncException.invalidResponse();
    return value;
  }

  private static int nonNegativeInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 0) throw IncrementalSyncException.invalidResponse();
    return value;
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw IncrementalSyncException.invalidResponse();
    }
    return node.asInt();
  }
}
