package com.timingjeju.api.application.tourapi.detailitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class DetailItemAttributesTest {
  private static final String SCHEMA = "tour-api.detailInfo2.info";
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void serialized_JSON_UTF8_전체가_정확히_64KiB이면_허용하고_escaping으로_넘으면_거부한다() throws Exception {
    String escapedMultibytePrefix = "제주 \"인용\" \\ 경로\n\t";
    int remaining = DetailItemAttributes.MAX_BYTES - serializedBytes(escapedMultibytePrefix).length;
    String exactBoundary = escapedMultibytePrefix + "a".repeat(remaining);

    assertThat(serializedBytes(exactBoundary)).hasSize(DetailItemAttributes.MAX_BYTES);
    assertThatCode(() -> new DetailItemAttributes(SCHEMA, 1, Map.of("infotext", exactBoundary)))
        .doesNotThrowAnyException();

    String escapedBeyondBoundary = exactBoundary + "a";
    assertThat(serializedBytes(escapedBeyondBoundary)).hasSize(DetailItemAttributes.MAX_BYTES + 1);
    assertThatThrownBy(
            () -> new DetailItemAttributes(SCHEMA, 1, Map.of("infotext", escapedBeyondBoundary)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("크기 제한");
  }

  private byte[] serializedBytes(String value) throws Exception {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("schema", SCHEMA);
    document.put("version", 1);
    document.put("fields", Map.of("infotext", value));
    return objectMapper.writeValueAsBytes(document);
  }
}
