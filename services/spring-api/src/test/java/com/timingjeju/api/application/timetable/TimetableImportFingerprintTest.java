package com.timingjeju.api.application.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TimetableImportFingerprintTest {
  @Test
  void raw와_mapping_manifest_record_omission의_모든_경계를_fingerprint에_포함한다() {
    String base =
        TimetableImportFingerprint.compute(
            "a".repeat(64), "b".repeat(64), "{\"recordKeys\":[]}", List.of("rk"), List.of("om"));

    assertThat(
            TimetableImportFingerprint.compute(
                "c".repeat(64),
                "b".repeat(64),
                "{\"recordKeys\":[]}",
                List.of("rk"),
                List.of("om")))
        .isNotEqualTo(base);
    assertThat(
            TimetableImportFingerprint.compute(
                "a".repeat(64),
                "c".repeat(64),
                "{\"recordKeys\":[]}",
                List.of("rk"),
                List.of("om")))
        .isNotEqualTo(base);
    assertThat(
            TimetableImportFingerprint.compute(
                "a".repeat(64), "b".repeat(64), "{}", List.of("rk"), List.of("om")))
        .isNotEqualTo(base);
    assertThat(
            TimetableImportFingerprint.compute(
                "a".repeat(64),
                "b".repeat(64),
                "{\"recordKeys\":[]}",
                List.of("other"),
                List.of("om")))
        .isNotEqualTo(base);
    assertThat(
            TimetableImportFingerprint.compute(
                "a".repeat(64),
                "b".repeat(64),
                "{\"recordKeys\":[]}",
                List.of("rk"),
                List.of("other")))
        .isNotEqualTo(base);
  }

  @Test
  void length_delimited_encoding은_서로다른_필드분할을_같은_fingerprint로_합치지_않는다() {
    String first =
        TimetableImportFingerprint.compute(
            "a".repeat(64), "b".repeat(64), "m", List.of("ab", "c"), List.of());
    String second =
        TimetableImportFingerprint.compute(
            "a".repeat(64), "b".repeat(64), "m", List.of("a", "bc"), List.of());

    assertThat(first).matches("[0-9a-f]{64}").isNotEqualTo(second);
  }
}
