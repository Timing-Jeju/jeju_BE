package com.timingjeju.api.domain.weather;

/** Converts WGS84 coordinates to the KMA Digital Forecast System's 5 km grid. */
public final class KmaGridConverter {

  // KMA DFS Lambert conformal conic parameters from the official grid-area specification.
  private static final double EARTH_RADIUS_KM = 6371.00877;
  private static final double GRID_SPACING_KM = 5.0;
  private static final double FIRST_STANDARD_PARALLEL_DEGREES = 30.0;
  private static final double SECOND_STANDARD_PARALLEL_DEGREES = 60.0;
  private static final double ORIGIN_LONGITUDE_DEGREES = 126.0;
  private static final double ORIGIN_LATITUDE_DEGREES = 38.0;
  private static final double ORIGIN_X = 43.0;
  private static final double ORIGIN_Y = 136.0;
  private static final int MAX_X = 149;
  private static final int MAX_Y = 253;

  private static final Projection PROJECTION = Projection.officialDfs();

  public KmaGridPoint convert(double latitude, double longitude) {
    validate(latitude, longitude);

    double radius = PROJECTION.radiusAt(latitude);
    double theta = PROJECTION.longitudeAngle(longitude);
    int nx = nearestGridPoint(radius * Math.sin(theta) + ORIGIN_X);
    int ny = nearestGridPoint(PROJECTION.originRadius() - radius * Math.cos(theta) + ORIGIN_Y);

    if (nx < 1 || nx > MAX_X || ny < 1 || ny > MAX_Y) {
      throw new IllegalArgumentException("위경도가 KMA DFS 격자 범위를 벗어났습니다.");
    }
    return new KmaGridPoint(nx, ny);
  }

  private static int nearestGridPoint(double projectedCoordinate) {
    return (int) Math.floor(projectedCoordinate + 0.5);
  }

  private static void validate(double latitude, double longitude) {
    if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
      throw new IllegalArgumentException("위경도는 유한한 숫자여야 합니다.");
    }
    if (latitude <= -90.0 || latitude >= 90.0) {
      throw new IllegalArgumentException("위도는 -90도보다 크고 90도보다 작아야 합니다.");
    }
    if (longitude < -180.0 || longitude > 180.0) {
      throw new IllegalArgumentException("위경도 범위가 올바르지 않습니다.");
    }
  }

  /**
   * Precomputed terms of the Lambert conformal conic equations. Keeping their derivation here makes
   * the conversion auditable instead of relying on an opaque coordinate snippet.
   */
  private record Projection(
      double earthRadiusInGridUnits,
      double coneConstant,
      double scaleFactor,
      double originLongitudeRadians,
      double originRadius) {

    private static Projection officialDfs() {
      double degreesToRadians = Math.PI / 180.0;
      double earthRadiusInGridUnits = EARTH_RADIUS_KM / GRID_SPACING_KM;
      double firstParallel = FIRST_STANDARD_PARALLEL_DEGREES * degreesToRadians;
      double secondParallel = SECOND_STANDARD_PARALLEL_DEGREES * degreesToRadians;
      double originLatitude = ORIGIN_LATITUDE_DEGREES * degreesToRadians;

      double parallelRatio =
          Math.tan(Math.PI / 4.0 + secondParallel / 2.0)
              / Math.tan(Math.PI / 4.0 + firstParallel / 2.0);
      double coneConstant =
          Math.log(Math.cos(firstParallel) / Math.cos(secondParallel)) / Math.log(parallelRatio);
      double firstParallelTangent = Math.tan(Math.PI / 4.0 + firstParallel / 2.0);
      double scaleFactor =
          Math.pow(firstParallelTangent, coneConstant) * Math.cos(firstParallel) / coneConstant;
      double originTangent = Math.tan(Math.PI / 4.0 + originLatitude / 2.0);
      double originRadius =
          earthRadiusInGridUnits * scaleFactor / Math.pow(originTangent, coneConstant);

      return new Projection(
          earthRadiusInGridUnits,
          coneConstant,
          scaleFactor,
          ORIGIN_LONGITUDE_DEGREES * degreesToRadians,
          originRadius);
    }

    private double radiusAt(double latitude) {
      double latitudeRadians = Math.toRadians(latitude);
      double tangent = Math.tan(Math.PI / 4.0 + latitudeRadians / 2.0);
      return earthRadiusInGridUnits * scaleFactor / Math.pow(tangent, coneConstant);
    }

    private double longitudeAngle(double longitude) {
      double difference = Math.toRadians(longitude) - originLongitudeRadians;
      if (difference > Math.PI) {
        difference -= 2.0 * Math.PI;
      } else if (difference < -Math.PI) {
        difference += 2.0 * Math.PI;
      }
      return difference * coneConstant;
    }
  }
}
