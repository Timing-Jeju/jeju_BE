package com.timingjeju.api.global.tourapi.place;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.place.PlaceListImportException;
import com.timingjeju.api.application.tourapi.place.PlaceListPage;
import com.timingjeju.api.application.tourapi.place.PlaceListParser;
import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import com.timingjeju.api.application.tourapi.place.TourPlace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TourApiPlaceListParser implements PlaceListParser {

  private static final double MIN_LONGITUDE = 126.0;
  private static final double MAX_LONGITUDE = 127.0;
  private static final double MIN_LATITUDE = 33.0;
  private static final double MAX_LATITUDE = 34.0;
  private static final DateTimeFormatter MODIFIED_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
  private static final ZoneId JEJU_ZONE = ZoneId.of("Asia/Seoul");
  private static final Set<String> OPTIONAL_TEXT_FIELDS =
      Set.of(
          "addr1",
          "addr2",
          "firstimage",
          "firstimage2",
          "modifiedtime",
          "lDongRegnCd",
          "lDongSignguCd",
          "lclsSystm1",
          "lclsSystm2",
          "lclsSystm3");
  private final ObjectReader strictReader;

  public TourApiPlaceListParser(ObjectMapper objectMapper) {
    strictReader =
        Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.")
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public PlaceListPage parse(SnapshotPayloadFormat format, byte[] payload) {
    Objects.requireNonNull(format, "format은 필수입니다.");
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    if (format != SnapshotPayloadFormat.JSON) {
      throw PlaceListImportException.invalidResponse();
    }
    try {
      JsonNode response = strictReader.readTree(payload).path("response");
      JsonNode resultCode = response.path("header").path("resultCode");
      if (!resultCode.isTextual() || !"0000".equals(resultCode.asString())) {
        throw PlaceListImportException.invalidResponse();
      }
      JsonNode body = response.path("body");
      int pageNo = positiveInt(body.path("pageNo"));
      int numOfRows = positiveInt(body.path("numOfRows"));
      int totalCount = nonNegativeInt(body.path("totalCount"));
      JsonNode itemNode = body.path("items").path("item");
      if (!itemNode.isArray()) {
        throw PlaceListImportException.invalidResponse();
      }
      List<TourPlace> places = new ArrayList<>();
      Map<PlaceRejectReason, Integer> rejected = new EnumMap<>(PlaceRejectReason.class);
      for (JsonNode item : itemNode) {
        PlaceRejectReason reason = parseItem(item, places);
        if (reason != null) {
          rejected.merge(reason, 1, Integer::sum);
        }
      }
      return new PlaceListPage(pageNo, numOfRows, totalCount, itemNode.size(), places, rejected);
    } catch (PlaceListImportException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw PlaceListImportException.invalidResponse();
    }
  }

  private static PlaceRejectReason parseItem(JsonNode item, List<TourPlace> places) {
    if (!item.isObject()) {
      return PlaceRejectReason.INVALID_FIELD;
    }
    String contentId = requiredText(item, "contentid", 512);
    String contentTypeId = requiredText(item, "contenttypeid", 128);
    if (contentId == null || contentTypeId == null) {
      return PlaceRejectReason.MISSING_REQUIRED_FIELD;
    }
    String title = requiredText(item, "title");
    if (title == null || containsHtml(title)) {
      return PlaceRejectReason.INVALID_TITLE;
    }
    Double longitude = coordinate(item, "mapx");
    Double latitude = coordinate(item, "mapy");
    if (longitude == null
        || latitude == null
        || longitude < MIN_LONGITUDE
        || longitude > MAX_LONGITUDE
        || latitude < MIN_LATITUDE
        || latitude > MAX_LATITUDE) {
      return PlaceRejectReason.INVALID_COORDINATE;
    }
    if (OPTIONAL_TEXT_FIELDS.stream()
        .anyMatch(field -> item.has(field) && !item.path(field).isTextual())) {
      return PlaceRejectReason.INVALID_FIELD;
    }
    String regionCode = optionalText(item, "lDongRegnCd");
    if (regionCode != null && !"50".equals(regionCode)) {
      return PlaceRejectReason.OUT_OF_SCOPE;
    }
    Instant modified = modifiedAt(optionalText(item, "modifiedtime"));
    if (item.has("modifiedtime") && modified == null) {
      return PlaceRejectReason.INVALID_FIELD;
    }
    places.add(
        new TourPlace(
            contentId,
            contentTypeId,
            title,
            longitude,
            latitude,
            optionalText(item, "addr1"),
            optionalText(item, "addr2"),
            optionalText(item, "firstimage"),
            optionalText(item, "firstimage2"),
            regionCode,
            optionalText(item, "lDongSignguCd"),
            optionalText(item, "lclsSystm1"),
            optionalText(item, "lclsSystm2"),
            optionalText(item, "lclsSystm3"),
            modified));
    return null;
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

  private static Double coordinate(JsonNode item, String field) {
    String value = requiredText(item, field);
    if (value == null) {
      return null;
    }
    try {
      double coordinate = Double.parseDouble(value);
      return Double.isFinite(coordinate) ? coordinate : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Instant modifiedAt(String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(value, MODIFIED_FORMAT).atZone(JEJU_ZONE).toInstant();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private static String requiredText(JsonNode item, String field) {
    return requiredText(item, field, 1024);
  }

  private static String requiredText(JsonNode item, String field, int maxBytes) {
    JsonNode value = item.path(field);
    if (!value.isTextual()) {
      return null;
    }
    String normalized = value.asString().strip();
    return normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes
        ? null
        : normalized;
  }

  private static String optionalText(JsonNode item, String field) {
    if (!item.has(field)) {
      return null;
    }
    return requiredText(item, field);
  }

  private static int positiveInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 1) {
      throw PlaceListImportException.invalidResponse();
    }
    return value;
  }

  private static int nonNegativeInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 0) {
      throw PlaceListImportException.invalidResponse();
    }
    return value;
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw PlaceListImportException.invalidResponse();
    }
    return node.asInt();
  }
}
