package com.timingjeju.api.domain.savedplaces.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.savedplaces.model.SavedPlaceEtag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlaceEtagTest {
  @Test
  void strong_ETag는_canonical_placeId와_updatedAt만_hash한다() throws Exception {
    UUID placeId = UUID.fromString("20000000-0000-0000-0000-000000000003");
    Instant updatedAt = Instant.parse("2026-08-03T00:05:00.123456Z");
    String canonical = placeId + "\n" + updatedAt;
    String expected =
        "\"sp-"
            + HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 32)
            + "\"";

    assertThat(SavedPlaceEtag.strong(placeId, updatedAt)).isEqualTo(expected);
  }
}
