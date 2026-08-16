package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.image.DetailImageParser;
import com.timingjeju.api.application.tourapi.image.PlaceImage;
import com.timingjeju.api.application.tourapi.image.PlaceImageImportException;
import com.timingjeju.api.application.tourapi.image.PlaceImagePage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TourApiDetailImageParser implements DetailImageParser {
  private static final int MAX_URL_BYTES = 8192;
  private static final int MAX_ID_BYTES = 512;
  private static final int MAX_TEXT_BYTES = 8192;
  private final ObjectReader reader;

  public TourApiDetailImageParser(ObjectMapper objectMapper) {
    this.reader = objectMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public PlaceImagePage parse(SnapshotPayloadFormat format, byte[] payload, String contentId) {
    if (format != SnapshotPayloadFormat.JSON
        || payload == null
        || contentId == null
        || contentId.isBlank()) {
      throw PlaceImageImportException.invalidResponse();
    }
    try {
      JsonNode response = reader.readTree(payload).path("response");
      if (!response.path("header").path("resultCode").isTextual()
          || !"0000".equals(response.path("header").path("resultCode").asString())) {
        throw PlaceImageImportException.invalidResponse();
      }
      JsonNode body = response.path("body");
      int pageNo = positiveInt(body.path("pageNo"));
      int numOfRows = positiveInt(body.path("numOfRows"));
      int totalCount = nonNegativeInt(body.path("totalCount"));
      JsonNode itemsNode = body.path("items");
      if (!body.isObject() || itemsNode.isMissingNode()) {
        throw PlaceImageImportException.invalidResponse();
      }
      if ((itemsNode.isNull() || (itemsNode.isTextual() && itemsNode.asString().isBlank()))
          && totalCount == 0) {
        return new PlaceImagePage(contentId, pageNo, numOfRows, 0, 0, List.of());
      }
      JsonNode itemNodes = itemsNode.path("item");
      if (!itemNodes.isArray()) throw PlaceImageImportException.invalidResponse();
      List<PlaceImage> images = new ArrayList<>();
      for (int index = 0; index < itemNodes.size(); index++) {
        JsonNode item = itemNodes.get(index);
        if (!item.isObject() || !contentId.equals(requiredText(item, "contentid", MAX_ID_BYTES))) {
          throw PlaceImageImportException.invalidResponse();
        }
        images.add(
            new PlaceImage(
                optionalText(item, "serialnum", MAX_ID_BYTES),
                safeUrl(requiredText(item, "originimgurl", MAX_URL_BYTES)),
                safeOptionalUrl(item, "smallimageurl"),
                optionalText(item, "imgname", MAX_TEXT_BYTES),
                optionalText(item, "cpyrhtDivCd", MAX_TEXT_BYTES),
                optionalText(item, "copyrightowner", MAX_TEXT_BYTES),
                optionalText(item, "license", MAX_TEXT_BYTES),
                index + 1));
      }
      return new PlaceImagePage(contentId, pageNo, numOfRows, totalCount, itemNodes.size(), images);
    } catch (PlaceImageImportException failure) {
      throw failure;
    } catch (Exception ignored) {
      throw PlaceImageImportException.invalidResponse();
    }
  }

  private static String safeOptionalUrl(JsonNode item, String field) {
    String value = optionalText(item, field, MAX_URL_BYTES);
    return value == null ? null : safeUrl(value);
  }

  private static String safeUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute()
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null) {
        throw PlaceImageImportException.invalidResponse();
      }
      return uri.toASCIIString();
    } catch (IllegalArgumentException failure) {
      throw PlaceImageImportException.invalidResponse();
    }
  }

  private static String requiredText(JsonNode item, String field, int maxBytes) {
    String value = optionalText(item, field, maxBytes);
    if (value == null) throw PlaceImageImportException.invalidResponse();
    return value;
  }

  private static String optionalText(JsonNode item, String field, int maxBytes) {
    if (!item.has(field) || item.path(field).isNull()) return null;
    JsonNode value = item.path(field);
    if (!value.isTextual()) throw PlaceImageImportException.invalidResponse();
    String text = value.asString().strip();
    if (text.isEmpty()) return null;
    if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw PlaceImageImportException.invalidResponse();
    }
    return text;
  }

  private static int positiveInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 1) throw PlaceImageImportException.invalidResponse();
    return value;
  }

  private static int nonNegativeInt(JsonNode node) {
    int value = exactInt(node);
    if (value < 0) throw PlaceImageImportException.invalidResponse();
    return value;
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw PlaceImageImportException.invalidResponse();
    }
    return node.asInt();
  }
}
