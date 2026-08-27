package com.timingjeju.api.domain.savedplaces.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SavedPlacesQueryTest {
  @Test
  void filter는_trim_NFC와_default를_canonicalize한다() {
    var query = SavedPlacesQuery.of(" 동쪽 ", "VE", "seongsan", null, null, null);
    assertThat(query.tag()).isEqualTo("동쪽");
    assertThat(query.sort()).isEqualTo("saved_at_desc");
    assertThat(query.size()).isEqualTo(20);
  }

  @Test
  void category_region_sort_cursor_size_invalid경계를_거부한다() {
    assertInvalid(null, "ve", null, null, null, 20);
    assertInvalid(null, null, "성산", null, null, 20);
    assertInvalid(null, null, null, "unknown", null, 20);
    assertInvalid(null, null, null, null, "", 20);
    assertInvalid(null, null, null, null, null, 101);
  }

  @Test
  void tag길이는_Unicode_code_point로_계산한다() {
    assertThat(SavedPlacesQuery.of("😀".repeat(50), null, null, null, null, 20).tag())
        .isEqualTo("😀".repeat(50));
    assertInvalid("😀".repeat(51), null, null, null, null, 20);
  }

  private static void assertInvalid(
      String tag, String category, String region, String sort, String cursor, Integer size) {
    assertThatThrownBy(() -> SavedPlacesQuery.of(tag, category, region, sort, cursor, size))
        .isInstanceOf(SavedPlaceException.class)
        .extracting("code")
        .isEqualTo("INVALID_QUERY_PARAMETER");
  }
}
