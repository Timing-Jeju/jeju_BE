package com.timingjeju.api.application.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CursorFilterFingerprintTest {

  @Test
  void filter_fingerprint는_NFC_trim_collection정렬_null_empty_정규화를_적용한다() {
    String decomposed = "성산";
    String composed = "성산";
    Map<String, Object> firstFilters = new HashMap<>();
    firstFilters.put("query", "  " + decomposed + "  ");
    firstFilters.put("categories", List.of("museum", " beach ", "museum"));
    firstFilters.put("ignoredBlank", "  ");
    firstFilters.put("ignoredEmpty", List.of());
    Map<String, Object> secondFilters = new HashMap<>();
    secondFilters.put("categories", List.of("museum", "museum", "beach"));
    secondFilters.put("query", composed);
    secondFilters.put("ignoredNull", null);

    String first = CursorFilterFingerprint.sha256(firstFilters);
    String second = CursorFilterFingerprint.sha256(secondFilters);

    assertThat(first).isEqualTo(second);
    assertThat(first).matches("[0-9a-f]{64}");
  }

  @Test
  void filter_fingerprint는_정렬된_canonical_JSON의_SHA_256이다() {
    String fingerprint =
        CursorFilterFingerprint.sha256(
            Map.of("query", " 성산 ", "tags", List.of("z", "a"), "page", 1));

    assertThat(fingerprint)
        .isEqualTo("c9399c51479f217bc7484328b3f5eb72774c3d36fe4f0eb902639557b8329cb5");
  }
}
