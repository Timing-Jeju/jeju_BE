package com.timingjeju.api.domain.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class KmaGridConverterTest {

  private final KmaGridConverter converter = new KmaGridConverter();

  @ParameterizedTest(name = "{0} 좌표는 KMA 격자 ({3}, {4})로 변환된다")
  @CsvSource({
    "제주국제공항, 33.507078, 126.492769, 52, 38",
    "성산일출봉, 33.458111, 126.941516, 60, 37",
    "제주시청, 33.499534, 126.531171, 53, 38",
    "서귀포시청, 33.253925, 126.559787, 53, 33"
  })
  void convertsJejuGoldenCoordinates(
      String place, double latitude, double longitude, int expectedNx, int expectedNy) {
    assertThat(converter.convert(latitude, longitude))
        .as(place)
        .isEqualTo(new KmaGridPoint(expectedNx, expectedNy));
  }

  @Test
  void roundsToNearestDotGridPoint() {
    // Projected x is about 52.423 and 52.514 respectively, on either side of x=52.5.
    assertThat(converter.convert(33.489340, 126.515000)).isEqualTo(new KmaGridPoint(52, 38));
    assertThat(converter.convert(33.489340, 126.520000)).isEqualTo(new KmaGridPoint(53, 38));
  }

  @ParameterizedTest(name = "공식 DFS {0} 모서리는 격자 ({3}, {4})다")
  @CsvSource({
    "좌하단, 31.7944, 123.7613, 1, 1",
    "우하단, 31.6518, 131.6423, 149, 1",
    "좌상단, 43.3935, 123.3102, 1, 253",
    "우상단, 43.2175, 132.7750, 149, 253"
  })
  void convertsOfficialDfsGridCorners(
      String corner, double latitude, double longitude, int expectedNx, int expectedNy) {
    assertThat(converter.convert(latitude, longitude))
        .as(corner)
        .isEqualTo(new KmaGridPoint(expectedNx, expectedNy));
  }

  @ParameterizedTest
  @CsvSource({"-90.000001, 126.5", "90.000001, 126.5", "33.5, -180.000001", "33.5, 180.000001"})
  void rejectsCoordinatesOutsideGeographicBounds(double latitude, double longitude) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> converter.convert(latitude, longitude))
        .withMessageContaining("위");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
  void rejectsNonFiniteLatitude(double latitude) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> converter.convert(latitude, 126.5))
        .withMessageContaining("위경도");
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
  void rejectsNonFiniteLongitude(double longitude) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> converter.convert(33.5, longitude))
        .withMessageContaining("위경도");
  }

  @ParameterizedTest
  @ValueSource(doubles = {-90.0, 90.0})
  void rejectsPolesWhereLambertProjectionIsUndefined(double latitude) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> converter.convert(latitude, 126.5))
        .withMessageContaining("위도");
  }

  @ParameterizedTest
  @CsvSource({"0.0, 0.0", "38.0, -180.0", "38.0, 180.0"})
  void rejectsLegalGeographicCoordinatesOutsideTheDfsGrid(double latitude, double longitude) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> converter.convert(latitude, longitude))
        .withMessageContaining("DFS 격자 범위");
  }
}
