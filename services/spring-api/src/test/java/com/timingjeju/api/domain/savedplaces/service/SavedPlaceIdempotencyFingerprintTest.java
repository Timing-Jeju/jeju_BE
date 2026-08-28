package com.timingjeju.api.domain.savedplaces.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceIdempotencyFingerprint;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlaceIdempotencyFingerprintTest {
  private static final UUID PLACE_ID = UUID.fromString("34200000-0000-0000-0000-000000000011");

  @Test
  void null과_literal_null은_서로_다른_fingerprint다() {
    var absent = command(null, List.of("동쪽"), 1, null);
    var literal = command("null", List.of("동쪽"), 1, null);

    assertThat(SavedPlaceIdempotencyFingerprint.sha256(absent))
        .isNotEqualTo(SavedPlaceIdempotencyFingerprint.sha256(literal));
  }

  @Test
  void length_frame은_NUL과_tag_delimiter_boundary_collision을_막는다() {
    var nulInMemo = new SavedPlaceCommand(PLACE_ID, "a\u0000b", List.of("c"), 1, 2);
    var nulInTag = new SavedPlaceCommand(PLACE_ID, "a", List.of("b\u0000c"), 1, 2);
    var splitTags = new SavedPlaceCommand(PLACE_ID, null, List.of("a", "b"), 1, 2);
    var delimiterInTag = new SavedPlaceCommand(PLACE_ID, null, List.of("a\u0001b"), 1, 2);

    assertThat(SavedPlaceIdempotencyFingerprint.sha256(nulInMemo))
        .isNotEqualTo(SavedPlaceIdempotencyFingerprint.sha256(nulInTag));
    assertThat(SavedPlaceIdempotencyFingerprint.sha256(splitTags))
        .isNotEqualTo(SavedPlaceIdempotencyFingerprint.sha256(delimiterInTag));
  }

  @Test
  void array_order와_number_field는_각각_typed_payload에_포함된다() {
    var baseline = command("메모", List.of("a", "b"), 1, 2);

    assertThat(SavedPlaceIdempotencyFingerprint.sha256(baseline))
        .isNotEqualTo(
            SavedPlaceIdempotencyFingerprint.sha256(command("메모", List.of("b", "a"), 1, 2)))
        .isNotEqualTo(
            SavedPlaceIdempotencyFingerprint.sha256(command("메모", List.of("a", "b"), 2, 1)));
  }

  @Test
  void placeId_memo_tags_priority_targetDay의_각_mutation은_fingerprint를_바꾼다() {
    var baseline = command("메모", List.of("a", "b"), 1, 2);
    String fingerprint = SavedPlaceIdempotencyFingerprint.sha256(baseline);

    assertThat(
            List.of(
                SavedPlaceIdempotencyFingerprint.sha256(
                    new SavedPlaceCommand(
                        UUID.fromString("34200000-0000-0000-0000-000000000012"),
                        "메모",
                        List.of("a", "b"),
                        1,
                        2)),
                SavedPlaceIdempotencyFingerprint.sha256(command("다른 메모", List.of("a", "b"), 1, 2)),
                SavedPlaceIdempotencyFingerprint.sha256(command("메모", List.of("a", "c"), 1, 2)),
                SavedPlaceIdempotencyFingerprint.sha256(command("메모", List.of("a", "b"), 2, 2)),
                SavedPlaceIdempotencyFingerprint.sha256(command("메모", List.of("a", "b"), 1, null))))
        .doesNotContain(fingerprint);
  }

  @Test
  void UTF8_NFC는_동등한_text에_결정적인_fingerprint를_생성한다() {
    var nfc = command("동쪽", List.of("필수"), 1, 2);
    var nfd = command("동쪽", List.of("필수"), 1, 2);

    assertThat(SavedPlaceIdempotencyFingerprint.sha256(nfd))
        .isEqualTo(SavedPlaceIdempotencyFingerprint.sha256(nfc))
        .isEqualTo("5bacb8653e04929e5ec5b9e38099877f9841c8054766036c20479f2044ff3ed7");
  }

  private static SavedPlaceCommand command(
      String memo, List<String> tags, int priority, Integer targetDay) {
    return new SavedPlaceCommand(PLACE_ID, memo, tags, priority, targetDay);
  }
}
