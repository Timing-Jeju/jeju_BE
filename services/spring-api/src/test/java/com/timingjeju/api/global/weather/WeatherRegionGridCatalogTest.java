package com.timingjeju.api.global.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WeatherRegionGridCatalogTest {
  @Test
  void 공식_260701_행정구역_행의_정확한_대표격자를_사용한다() {
    var catalog = WeatherRegionGridCatalog.load();
    assertThat(catalog.find("jeju-si"))
        .get()
        .extracting("gridPoint.nx", "gridPoint.ny")
        .containsExactly(53, 38);
    assertThat(catalog.find("seogwipo-si"))
        .get()
        .extracting("gridPoint.nx", "gridPoint.ny")
        .containsExactly(53, 33);
    assertThat(catalog.find("seongsan"))
        .get()
        .extracting("gridPoint.nx", "gridPoint.ny")
        .containsExactly(60, 37);
    assertThat(catalog.find("unknown")).isEmpty();
  }

  @Test
  void 중복_지역과_잘못된_grid_자료는_시작_시점에_거부한다() {
    String header = "regionCode,administrativeCode,regionName,nx,ny\n";
    String row = "jeju-si,5011000000,제주시,53,38\n";
    for (String csv :
        new String[] {
          header + row + row,
          header + "jeju-si,5011000000,제주시,0,38\n",
          header,
          "wrong-header\n" + row
        }) {
      assertThatThrownBy(() -> WeatherRegionGridCatalog.parse(csv))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
