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
  void 생략값은_전체필터_기본size20_savedOnly_false로_정규화한다() {
    PlacesListQuery query =
        PlacesListQuery.of(null, null, null, null, null, null, null, null, null);

    assertThat(query.query()).isNull();
    assertThat(query.radiusMeters()).isNull();
    assertThat(query.size()).isEqualTo(20);
    assertThat(query.savedOnly()).isFalse();
    assertThat(query.nearby()).isFalse();
  }

  @Test
  void 검색어는_trim하고_size와_radius의_양끝_경계를_허용한다() {
    PlacesListQuery minimum =
        PlacesListQuery.of("  성산  ", "VE", "seongsan", 33.45, 126.94, 100, null, 1, true);
    PlacesListQuery maximum =
        PlacesListQuery.of(
            "성산", "content-type:99", "seongsan", 33.45, 126.94, 50_000, null, 100, false);

    assertThat(minimum.query()).isEqualTo("성산");
    assertThat(minimum.size()).isEqualTo(1);
    assertThat(minimum.radiusMeters()).isEqualTo(100);
    assertThat(maximum.size()).isEqualTo(100);
    assertThat(maximum.radiusMeters()).isEqualTo(50_000);
  }

  @Test
  void trim한_query의_정확한_길이_1과_100을_허용한다() {
    PlacesListQuery minimum =
        PlacesListQuery.of("  가  ", null, null, null, null, null, null, 20, false);
    PlacesListQuery maximum =
        PlacesListQuery.of(
            "  " + "가".repeat(100) + "  ", null, null, null, null, null, null, 20, false);

    assertThat(minimum.query()).isEqualTo("가");
    assertThat(maximum.query()).hasSize(100);
  }

  @ParameterizedTest
  @MethodSource("invalidQueries")
  void 잘못된_query_pattern_size는_INVALID_QUERY_PARAMETER다(
      String query, String category, String region, Integer size) {
    assertThatThrownBy(
            () -> PlacesListQuery.of(query, category, region, null, null, null, null, size, false))
        .isInstanceOf(PlaceQueryValidationException.class)
        .extracting("code")
        .isEqualTo("INVALID_QUERY_PARAMETER");
  }

  @ParameterizedTest
  @MethodSource("invalidGeoFilters")
  void 좌표쌍_radius_제주범위가_잘못되면_INVALID_GEO_FILTER다(Double lat, Double lng, Integer radius) {
    assertThatThrownBy(
            () -> PlacesListQuery.of(null, null, null, lat, lng, radius, null, 20, false))
        .isInstanceOf(PlaceQueryValidationException.class)
        .extracting("code")
        .isEqualTo("INVALID_GEO_FILTER");
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

  static Stream<Arguments> invalidGeoFilters() {
    return Stream.of(
        Arguments.of(33.45, null, null),
        Arguments.of(null, 126.94, null),
        Arguments.of(null, null, 100),
        Arguments.of(33.45, 126.94, 99),
        Arguments.of(33.45, 126.94, 50_001),
        Arguments.of(32.0, 126.94, 1_000),
        Arguments.of(33.45, 130.0, 1_000));
  }
}
