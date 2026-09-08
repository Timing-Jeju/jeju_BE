package com.timingjeju.api.domain.weather.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.weather.exception.WeatherForecastException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class WeatherForecastQueryTest {
  private static final String TIME = "2026-08-03T15:00:00+09:00";
  private static final String ID = "20000000-0000-4000-8000-000000000001";

  @Test
  void 입력은_좌표_대신_명시_선택자와_시각만_허용한다() {
    assertThat(WeatherForecastQuery.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("regionCode", "placeId", "tripItemId", "dateTime");
    assertThat(WeatherForecastQuery.parse("jeju-si", null, null, TIME).regionCode())
        .isEqualTo("jeju-si");
    assertThat(WeatherForecastQuery.parse(null, ID, null, TIME).placeId())
        .isEqualTo(UUID.fromString(ID));
    assertThat(WeatherForecastQuery.parse(null, null, ID, TIME).tripItemId())
        .isEqualTo(UUID.fromString(ID));
  }

  @Test
  void 선택자가_없거나_둘_이상이면_거부한다() {
    for (String[] values :
        new String[][] {
          {null, null, null},
          {"jeju-si", ID, null},
          {null, ID, ID},
          {"jeju-si", null, ID},
          {"jeju-si", ID, ID}
        }) {
      assertThatThrownBy(() -> WeatherForecastQuery.parse(values[0], values[1], values[2], TIME))
          .isInstanceOf(WeatherForecastException.class)
          .extracting("code")
          .isEqualTo("INVALID_WEATHER_SELECTOR");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " Jeju-si", "jeju-si ", "JEJU", "33.45", "a/b", "서울"})
  void 비정규_지역_코드는_입력값_없이_거부한다(String value) {
    assertThatThrownBy(() -> WeatherForecastQuery.parse(value, null, null, TIME))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("INVALID_WEATHER_SELECTOR");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "1-1-1-1-1",
        "AAAAAAAA-0000-4000-8000-000000000001",
        " 20000000-0000-4000-8000-000000000001"
      })
  void 장소와_계획_UUID는_정규_소문자만_허용한다(String value) {
    assertThatThrownBy(() -> WeatherForecastQuery.parse(null, value, null, TIME))
        .isInstanceOf(WeatherForecastException.class);
    assertThatThrownBy(() -> WeatherForecastQuery.parse(null, null, value, TIME))
        .isInstanceOf(WeatherForecastException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "2026-08-03T15:00:00Z",
        "2026-08-03T15:30:00+09:00",
        "2026-08-03T15:00:00.000+09:00",
        "2026-02-30T15:00:00+09:00",
        "2026-08-03T15:00:00+09:00 "
      })
  void 실제_제주_정시만_허용한다(String value) {
    assertThatThrownBy(() -> WeatherForecastQuery.parse("jeju-si", null, null, value))
        .isInstanceOf(WeatherForecastException.class)
        .extracting("code")
        .isEqualTo("INVALID_WEATHER_SELECTOR");
  }
}
