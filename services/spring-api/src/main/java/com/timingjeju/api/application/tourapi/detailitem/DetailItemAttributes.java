package com.timingjeju.api.application.tourapi.detailitem;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DetailItemAttributes(String schema, int version, Map<String, String> fields) {
  public static final int MAX_BYTES = 65_536;

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
    int bytes = utf8(schema);
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || key.isBlank() || value == null) {
        throw new IllegalArgumentException("attributes는 문자열 key/value만 허용합니다.");
      }
      bytes += utf8(key) + utf8(value);
      if (bytes > MAX_BYTES) {
        throw new IllegalArgumentException("attributes 크기 제한을 초과했습니다.");
      }
      copy.put(key, value);
    }
    fields = Collections.unmodifiableMap(copy);
  }

  private static int utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
