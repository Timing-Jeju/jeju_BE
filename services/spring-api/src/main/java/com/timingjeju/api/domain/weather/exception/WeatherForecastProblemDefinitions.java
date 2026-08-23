package com.timingjeju.api.domain.weather.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WeatherForecastProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem(
            "INVALID_WEATHER_FORECAST_QUERY",
            "날씨 조회 조건이 올바르지 않습니다",
            400,
            "위도, 경도와 제주 현지 예보 시각을 올바른 형식으로 모두 입력해 주세요."),
        problem(
            "WEATHER_LOCATION_NOT_SUPPORTED",
            "지원하지 않는 날씨 조회 위치입니다",
            422,
            "제주 지역의 지원 가능한 위치를 입력해 주세요."),
        problem(
            "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED",
            "지원하지 않는 예보 기간입니다",
            422,
            "현재 정시부터 10일 이내의 제주 현지 시각을 입력해 주세요."),
        problem(
            "WEATHER_FORECAST_UNAVAILABLE",
            "날씨 예보를 불러올 수 없습니다",
            503,
            "최신 예보와 직전 예보를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."));
  }

  private static ProblemDefinition problem(String code, String title, int status, String detail) {
    return new ProblemDefinition(
        URI.create(
            "https://api.timing-jeju.com/problems/"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')),
        title,
        status,
        code,
        detail);
  }
}
