package com.timingjeju.api.global.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class McpSchemaFingerprint {
  private McpSchemaFingerprint() {}

  public static String sha256(Map<String, Object> schema, ObjectMapper objectMapper) {
    JsonNode node = objectMapper.valueToTree(schema);
    byte[] bytes = canonicalJson(node, objectMapper).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
    }
  }

  private static String canonicalJson(JsonNode node, ObjectMapper objectMapper) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(Map.Entry.comparingByKey());
      StringBuilder result = new StringBuilder("{");
      for (int index = 0; index < entries.size(); index++) {
        if (index > 0) result.append(',');
        Map.Entry<String, JsonNode> entry = entries.get(index);
        result
            .append(objectMapper.writeValueAsString(entry.getKey()))
            .append(':')
            .append(canonicalJson(entry.getValue(), objectMapper));
      }
      return result.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder result = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) result.append(',');
        result.append(canonicalJson(node.get(index), objectMapper));
      }
      return result.append(']').toString();
    }
    return node.toString();
  }
}
