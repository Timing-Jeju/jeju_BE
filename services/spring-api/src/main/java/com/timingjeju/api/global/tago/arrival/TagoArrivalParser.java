package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalPayloadParser;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class TagoArrivalParser implements TagoArrivalPayloadParser {
  private static final String SUCCESS = "00";
  private static final String RATE_LIMITED = "97";
  private final ObjectReader reader;

  public TagoArrivalParser(ObjectMapper objectMapper) {
    reader =
        Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.")
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public List<TagoArrival> parse(SnapshotPayloadFormat format, byte[] payload) {
    if (format != SnapshotPayloadFormat.JSON || payload == null) {
      throw TagoArrivalException.invalidResponse();
    }
    try {
      JsonNode response = object(reader.readTree(payload), "response");
      JsonNode header = object(response, "header");
      String code = text(header, "resultCode");
      text(header, "resultMsg");
      if (RATE_LIMITED.equals(code)) throw TagoArrivalException.rateLimited();
      if (!SUCCESS.equals(code)) throw TagoArrivalException.invalidResponse();
      JsonNode body = object(response, "body");
      int pageNo = nonNegative(body, "pageNo");
      int numOfRows = nonNegative(body, "numOfRows");
      int totalCount = nonNegative(body, "totalCount");
      if (pageNo != 1 || numOfRows < 1) throw TagoArrivalException.invalidResponse();
      JsonNode items = object(body, "items").path("item");
      List<JsonNode> rawItems = new ArrayList<>();
      if (items.isArray()) items.forEach(rawItems::add);
      else if (items.isObject()) rawItems.add(items);
      else if (!items.isMissingNode() && !items.isNull())
        throw TagoArrivalException.invalidResponse();
      if (totalCount == 0 || rawItems.isEmpty()) throw TagoArrivalException.emptyResult();
      if (rawItems.size() != totalCount || rawItems.size() > numOfRows) {
        throw TagoArrivalException.invalidResponse();
      }
      return rawItems.stream().map(TagoArrivalParser::arrival).toList();
    } catch (TagoArrivalException failure) {
      throw failure;
    } catch (RuntimeException ignored) {
      throw TagoArrivalException.invalidResponse();
    }
  }

  private static TagoArrival arrival(JsonNode item) {
    if (!item.isObject()) throw TagoArrivalException.invalidResponse();
    return new TagoArrival(
        text(item, "routeid"),
        routeNumber(item, "routeno"),
        optionalText(item, "routetp"),
        optionalText(item, "vehicletp"),
        boundedInteger(item, "arrtime", 0, 86_400),
        boundedInteger(item, "arrprevstationcnt", 0, 10_000));
  }

  private static JsonNode object(JsonNode parent, String name) {
    JsonNode node = parent == null ? null : parent.path(name);
    if (node == null || !node.isObject()) throw TagoArrivalException.invalidResponse();
    return node;
  }

  private static String text(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (!value.isTextual() || value.asString().isBlank()) {
      throw TagoArrivalException.invalidResponse();
    }
    return value.asString().strip();
  }

  private static String optionalText(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isTextual()) throw TagoArrivalException.invalidResponse();
    return value.asString().isBlank() ? null : value.asString().strip();
  }

  private static String routeNumber(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (value.isTextual()) return text(parent, name);
    if (!value.isIntegralNumber()) throw TagoArrivalException.invalidResponse();
    return value.asBigInteger().toString();
  }

  private static int boundedInteger(JsonNode parent, String name, int minimum, int maximum) {
    JsonNode value = parent.path(name);
    BigInteger integer;
    try {
      if (value.isTextual()) integer = new BigInteger(text(parent, name));
      else if (value.isIntegralNumber()) integer = value.asBigInteger();
      else throw TagoArrivalException.invalidResponse();
    } catch (NumberFormatException failure) {
      throw TagoArrivalException.invalidResponse();
    }
    if (integer.compareTo(BigInteger.valueOf(minimum)) < 0
        || integer.compareTo(BigInteger.valueOf(maximum)) > 0) {
      throw TagoArrivalException.invalidResponse();
    }
    return integer.intValueExact();
  }

  private static int nonNegative(JsonNode parent, String name) {
    JsonNode value = parent.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw TagoArrivalException.invalidResponse();
    }
    return value.intValue();
  }
}
