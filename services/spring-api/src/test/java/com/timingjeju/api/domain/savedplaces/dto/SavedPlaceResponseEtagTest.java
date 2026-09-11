package com.timingjeju.api.domain.savedplaces.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.savedplaces.model.SavedPlace;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceEtag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SavedPlaceResponseEtagTest {
  @Test
  void 목록과_수정_응답은_실제_row의_opaque_ETag를_필수로_제공한다() {
    UUID id = UUID.fromString("23800000-0000-0000-0000-000000000001");
    Instant saved = Instant.parse("2026-09-01T00:00:00Z");
    for (Instant updated : List.of(saved, saved.plusNanos(1000))) {
      var place =
          new SavedPlace(
              id, "저장 장소", "AT", "제주", null, null, null, List.of(), 3, null, saved, updated);
      var body = new JsonMapper().valueToTree(SavedPlaceResponse.from(place));
      assertThat(body.path("etag").asText()).isEqualTo(SavedPlaceEtag.strong(id, updated));
    }
  }
}
