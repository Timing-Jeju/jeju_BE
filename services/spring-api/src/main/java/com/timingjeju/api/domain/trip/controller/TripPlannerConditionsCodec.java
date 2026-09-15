package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripPlannerConditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class TripPlannerConditionsCodec {
  private final ObjectMapper mapper;

  TripPlannerConditionsCodec(ObjectMapper mapper) {
    this.mapper =
        mapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
  }

  TripPlannerConditions decode(byte[] body) {
    try {
      var root = mapper.readTree(body);
      fields(root, Set.of("dayAnchors", "styleCodes"));
      if (!root.get("dayAnchors").isArray() || !root.get("styleCodes").isArray())
        throw TripException.invalidRequest();
      var anchors = new ArrayList<TripPlannerConditions.DayAnchor>();
      for (var anchor : root.get("dayAnchors")) {
        fields(anchor, Set.of("dayId", "lodgingPlaceId"));
        anchors.add(
            new TripPlannerConditions.DayAnchor(
                uuid(anchor.get("dayId")), uuid(anchor.get("lodgingPlaceId"))));
      }
      var styles = new ArrayList<String>();
      for (var style : root.get("styleCodes")) {
        if (!style.isTextual()) throw TripException.invalidRequest();
        styles.add(style.asString());
      }
      return new TripPlannerConditions(anchors, styles);
    } catch (tools.jackson.core.JacksonException | IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
  }

  private static UUID uuid(JsonNode node) {
    if (node == null || !node.isTextual()) throw TripException.invalidRequest();
    var id = UUID.fromString(node.asString());
    if (!id.toString().equals(node.asString())) throw TripException.invalidRequest();
    return id;
  }

  private static void fields(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject()) throw TripException.invalidRequest();
    var actual = new HashSet<String>();
    node.properties().forEach(p -> actual.add(p.getKey()));
    if (!actual.equals(expected)) throw TripException.invalidRequest();
  }
}
