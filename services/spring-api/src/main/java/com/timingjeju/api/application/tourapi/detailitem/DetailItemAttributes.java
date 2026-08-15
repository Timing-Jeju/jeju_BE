package com.timingjeju.api.application.tourapi.detailitem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public record DetailItemAttributes(String schema, int version, Map<String, String> fields) {
  public static final int MAX_BYTES = 65_536;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public DetailItemAttributes {
    if (schema == null || !schema.matches("tour-api\\.detailInfo2\\.(info|course|room|menu)")) {
      throw new IllegalArgumentException("attributes schema가 올바르지 않습니다.");
    }
    if (version != 1) {
      throw new IllegalArgumentException("attributes version이 올바르지 않습니다.");
    }
    if (fields == null) {
      throw new IllegalArgumentException("attributes fields는 필수입니다.");
    }
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isBlank() || value == null) {
        throw new IllegalArgumentException("attributes는 문자열 key/value만 허용합니다.");
      }
      copy.put(key, value);
    }
    if (canonicalJsonBytes(schema, version, copy).length > MAX_BYTES) {
      throw new IllegalArgumentException("attributes 크기 제한을 초과했습니다.");
    }
    fields = Collections.unmodifiableMap(copy);
  }

  public String canonicalJson() {
    return new String(
        canonicalJsonBytes(schema, version, fields), java.nio.charset.StandardCharsets.UTF_8);
  }

  private static byte[] canonicalJsonBytes(String schema, int version, Map<String, String> fields) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("schema", schema);
    document.put("version", version);
    document.put("fields", fields);
    try {
      return OBJECT_MAPPER.writeValueAsBytes(document);
    } catch (JacksonException impossible) {
      throw new IllegalArgumentException("attributes JSON 직렬화에 실패했습니다.", impossible);
    }
  }
}
