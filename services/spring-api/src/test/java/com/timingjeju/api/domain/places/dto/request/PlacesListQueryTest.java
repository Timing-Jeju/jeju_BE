package com.timingjeju.api.domain.places.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class PlacesListQueryTest {

  @Test
  void 목록_입력_record에는_명시적_탐색_필드만_존재한다() {
    assertThat(PlacesListQuery.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("query", "category", "regionCode", "cursor", "size", "savedOnly");
  }

  @Test
  void 생략값은_전체필터_기본size20_savedOnly_false로_정규화한다() {
    PlacesListQuery query = PlacesListQuery.of(null, null, null, null, null, null);

    assertThat(query.query()).isNull();
    assertThat(query.size()).isEqualTo(20);
    assertThat(query.savedOnly()).isFalse();
  }

  @Test
  void 검색어는_trim하고_size의_양끝_경계를_허용한다() {
    PlacesListQuery minimum = PlacesListQuery.of("  성산  ", "VE", "seongsan", null, 1, true);
    PlacesListQuery maximum =
        PlacesListQuery.of("성산", "content-type:99", "seongsan", null, 100, false);

    assertThat(minimum.query()).isEqualTo("성산");
    assertThat(minimum.size()).isEqualTo(1);
    assertThat(maximum.size()).isEqualTo(100);
  }

  @Test
  void trim한_query의_정확한_길이_1과_100을_허용한다() {
    PlacesListQuery minimum = PlacesListQuery.of("  가  ", null, null, null, 20, false);
    PlacesListQuery maximum =
        PlacesListQuery.of("  " + "가".repeat(100) + "  ", null, null, null, 20, false);

    assertThat(minimum.query()).isEqualTo("가");
    assertThat(maximum.query()).hasSize(100);
  }

  @ParameterizedTest
  @MethodSource("invalidQueries")
  void 잘못된_query_pattern_size는_INVALID_QUERY_PARAMETER다(
      String query, String category, String region, Integer size) {
    assertThatThrownBy(() -> PlacesListQuery.of(query, category, region, null, size, false))
        .isInstanceOf(PlaceQueryValidationException.class)
        .extracting("code")
        .isEqualTo("INVALID_QUERY_PARAMETER");
  }

  static Stream<Arguments> invalidQueries() {
    return Stream.of(
        Arguments.of("   ", null, null, 20),
        Arguments.of("가".repeat(101), null, null, 20),
        Arguments.of(null, "tourist_attraction", null, 20),
        Arguments.of(null, " VE ", null, 20),
        Arguments.of(null, "VE\n", null, 20),
        Arguments.of(null, "API_KEY", null, 20),
        Arguments.of(null, null, "JEJU!", 20),
        Arguments.of(null, null, null, 0),
        Arguments.of(null, null, null, 101));
  }
}
