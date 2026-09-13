package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.trip.ReplaceTripDayActivityWindowsCommand;
import com.timingjeju.api.application.trip.TripDayActivityWindow;
import com.timingjeju.api.application.trip.TripException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class TripDayActivityWindowsRequestCodec {
  private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9]");
  private static final Set<String> DAY_FIELDS = Set.of("dayId", "startTime", "endTime");
  private final ObjectReader reader;

  TripDayActivityWindowsRequestCodec(ObjectMapper mapper) {
    reader =
        mapper
            .readerFor(JsonNode.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  ReplaceTripDayActivityWindowsCommand decode(byte[] body) {
    if (body == null
        || body.length == 0
        || body.length > TripPreferencesRequestBoundary.MAX_BODY_BYTES) {
      throw TripException.invalidRequest();
    }
    JsonNode root;
    try {
      root = reader.readValue(body);
    } catch (JacksonException failure) {
      throw TripException.invalidRequest();
    }
    requireFields(root, Set.of("days"));
    var days = root.get("days");
    if (!days.isArray()) throw TripException.invalidRequest();
    // Complete structural validation precedes domain validation and request hashing.
    for (var day : days) {
      requireFields(day, DAY_FIELDS);
      for (var field : DAY_FIELDS) {
        if (!day.get(field).isString()) throw TripException.invalidRequest();
      }
    }
    var result = new ArrayList<TripDayActivityWindow>();
    for (var day : days) {
      UUID id;
      String rawId = day.get("dayId").asText();
      try {
        id = UUID.fromString(rawId);
        if (!id.toString().equals(rawId)) throw TripException.invalidRequest();
      } catch (IllegalArgumentException failure) {
        throw TripException.invalidRequest();
      }
      result.add(
          new TripDayActivityWindow(id, time(day.get("startTime")), time(day.get("endTime"))));
    }
    return new ReplaceTripDayActivityWindowsCommand(result);
  }

  private static LocalTime time(JsonNode node) {
    String value = node.asText();
    if (!TIME.matcher(value).matches()) throw TripException.constraintViolation();
    return LocalTime.parse(value);
  }

  private static void requireFields(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject() || !node.propertyNames().equals(fields)) {
      throw TripException.invalidRequest();
    }
  }
}
