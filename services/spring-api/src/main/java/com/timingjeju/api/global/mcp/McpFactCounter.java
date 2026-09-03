package com.timingjeju.api.global.mcp;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class McpFactCounter {
  private McpFactCounter() {}

  static int count(Object value, ObjectMapper objectMapper) {
    Set<String> factIds = new HashSet<>();
    collect(objectMapper.valueToTree(value), null, factIds);
    return factIds.size();
  }

  private static void collect(JsonNode node, String field, Set<String> factIds) {
    if (node.isObject()) {
      for (var property : node.properties()) {
        collect(property.getValue(), property.getKey(), factIds);
      }
      return;
    }
    if (node.isArray()) {
      node.forEach(child -> collect(child, field, factIds));
      return;
    }
    if (node.isTextual() && field != null && isFactField(field)) factIds.add(node.asText());
  }

  private static boolean isFactField(String field) {
    return field.equals("fact_id")
        || field.equals("fact_ids")
        || field.equals("source_fact_id")
        || field.equals("evidence_fact_ids");
  }
}
