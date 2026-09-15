package com.timingjeju.api.global.mcp;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 과거 MCP schema가 허용하더라도 현재 위치와 그 파생 참조는 전송하지 않는다. */
final class McpNoLocationGuard {
  private static final Set<String> FORBIDDEN =
      Set.of(
          "currentposition",
          "currentlocation",
          "currentplaceid",
          "currentstopid",
          "currentrouteid",
          "currentregion",
          "currentregioncode",
          "nearestplaceid",
          "neareststopid",
          "nearestregion",
          "nearestregioncode",
          "coarselocation",
          "locationdigest",
          "locationhash",
          "locationsupplied",
          "locationprecisionmeters",
          "locationobservedat",
          "locationexpiresat",
          "locationredactedat",
          "grid100m",
          "gridx",
          "gridy",
          "geohash");

  private McpNoLocationGuard() {}

  static void validate(JsonNode node) {
    if (node.isObject()) {
      for (var field : node.properties()) {
        String key = field.getKey().replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (FORBIDDEN.contains(key)
            || (key.equals("type") && field.getValue().asString().equals("GRID_100M"))) {
          throw new McpContractException("MCP_USER_LOCATION_FORBIDDEN");
        }
        validate(field.getValue());
      }
    } else if (node.isArray()) {
      node.forEach(McpNoLocationGuard::validate);
    }
  }
}
