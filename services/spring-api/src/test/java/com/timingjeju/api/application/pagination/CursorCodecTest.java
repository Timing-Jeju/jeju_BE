package com.timingjeju.api.application.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CursorCodecTest {

  private static final CursorCodec CODEC =
      CursorCodec.hmacSha256("test-only-cursor-signing-key-32-bytes");
  private static final CursorSort CREATED_DESC = CursorSort.desc("createdAt", "id");
  private static final CursorContext PLACES_CONTEXT =
      new CursorContext(
          "/api/v1/places",
          CREATED_DESC,
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

  @Test
  void cursor는_endpoint_sort_tieBreaker_filterFingerprint를_담은_불투명_Base64URL이다() {
    String cursor =
        CODEC.encode(PLACES_CONTEXT, new CursorPosition("2026-08-05T12:00:00Z", "p-020"));

    assertThat(cursor).doesNotContain("2026-08-05").doesNotContain("/api/v1/places");
    assertThat(cursor).matches("[A-Za-z0-9_-]+");

    CursorPosition decoded = CODEC.decode(cursor, PLACES_CONTEXT);
    assertThat(decoded.sortValue()).isEqualTo("2026-08-05T12:00:00Z");
    assertThat(decoded.tieBreaker()).isEqualTo("p-020");
  }

  @Test
  void malformed_base64_oversized_cursor는_decode_전에_CURSOR_INVALID로_fail_closed한다() {
    assertThatThrownBy(() -> CODEC.decode("not a base64 cursor", PLACES_CONTEXT))
        .isInstanceOf(CursorInvalidException.class);

    assertThatThrownBy(() -> CODEC.decode("a".repeat(2049), PLACES_CONTEXT))
        .isInstanceOf(CursorInvalidException.class);

    String oversizedJson = "{\"v\":1,\"endpoint\":\"" + "x".repeat(1030) + "\"}";
    String oversizedCursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(oversizedJson.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> CODEC.decode(oversizedCursor, PLACES_CONTEXT))
        .isInstanceOf(CursorInvalidException.class);
  }

  @Test
  void endpoint_sort_filter_format_signature가_불일치하면_CURSOR_INVALID로_거부한다() {
    String cursor =
        CODEC.encode(PLACES_CONTEXT, new CursorPosition("2026-08-05T12:00:00Z", "p-020"));

    assertThatThrownBy(
            () ->
                CODEC.decode(
                    cursor,
                    new CursorContext(
                        "/api/v1/saved-places",
                        CREATED_DESC,
                        PLACES_CONTEXT.normalizedFilterFingerprint())))
        .isInstanceOf(CursorInvalidException.class);
    assertThatThrownBy(
            () ->
                CODEC.decode(
                    cursor,
                    new CursorContext(
                        "/api/v1/places",
                        CursorSort.asc("createdAt", "id"),
                        PLACES_CONTEXT.normalizedFilterFingerprint())))
        .isInstanceOf(CursorInvalidException.class);
    assertThatThrownBy(
            () ->
                CODEC.decode(
                    cursor,
                    new CursorContext(
                        "/api/v1/places",
                        CREATED_DESC,
                        "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd")))
        .isInstanceOf(CursorInvalidException.class);
    assertThatThrownBy(
            () -> CODEC.decode(cursor.substring(0, cursor.length() - 1) + "A", PLACES_CONTEXT))
        .isInstanceOf(CursorInvalidException.class);
  }
}
