package com.timingjeju.api.global.weather;

import com.timingjeju.api.domain.weather.KmaGridPoint;
import com.timingjeju.api.domain.weather.model.SupportedWeatherGrid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 기상청 260701 행정구역 표의 검토된 projection. 사용자 위치나 관광지 근접 관계로 파생하지 않는다. */
final class WeatherRegionGridCatalog {
  private final Map<String, SupportedWeatherGrid> regions;

  private WeatherRegionGridCatalog(Map<String, SupportedWeatherGrid> regions) {
    this.regions = Map.copyOf(regions);
  }

  static WeatherRegionGridCatalog load() {
    try (var input =
        WeatherRegionGridCatalog.class.getResourceAsStream("/weather/region-grids-260701.csv")) {
      if (input == null) {
        throw invalid();
      }
      return parse(new String(input.readNBytes(16385), StandardCharsets.UTF_8));
    } catch (IOException failure) {
      throw invalid();
    }
  }

  static WeatherRegionGridCatalog parse(String csv) {
    if (csv == null || csv.length() > 16384) {
      throw invalid();
    }
    String[] lines = csv.split("\\R");
    if (lines.length < 2 || !lines[0].equals("regionCode,administrativeCode,regionName,nx,ny")) {
      throw invalid();
    }
    Map<String, SupportedWeatherGrid> regions = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      String[] fields = lines[i].split(",", -1);
      if (fields.length != 5
          || !fields[0].matches("[a-z0-9][a-z0-9_-]{0,49}")
          || !fields[1].matches("[0-9]{10}")
          || fields[2].isBlank()
          || !fields[3].matches("[1-9][0-9]{0,2}")
          || !fields[4].matches("[1-9][0-9]{0,2}")) {
        throw invalid();
      }
      int nx = Integer.parseInt(fields[3]), ny = Integer.parseInt(fields[4]);
      if (nx > 149
          || ny > 253
          || regions.putIfAbsent(
                  fields[0], new SupportedWeatherGrid(new KmaGridPoint(nx, ny), fields[2]))
              != null) {
        throw invalid();
      }
    }
    return new WeatherRegionGridCatalog(regions);
  }

  Optional<SupportedWeatherGrid> find(String regionCode) {
    return Optional.ofNullable(regions.get(regionCode));
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("공개 지역 날씨 격자 자료가 올바르지 않습니다.");
  }
}
