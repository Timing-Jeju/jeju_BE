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
            "WEATHER_REFERENCE_NOT_FOUND", "계획 목적지를 찾을 수 없습니다", 404, "요청한 목적지가 없거나 조회할 수 없습니다."),
        problem(
            "INVALID_WEATHER_SELECTOR",
            "날씨 조회 조건이 올바르지 않습니다",
            400,
            "지역, 장소 또는 계획 항목 하나와 제주 예보 시각을 올바르게 입력해 주세요."),
        problem(
            "WEATHER_LOCATION_NOT_SUPPORTED",
            "지원하지 않는 날씨 조회 위치입니다",
            422,
            "제주 예보를 지원하는 공개 지역 또는 계획 목적지를 선택해 주세요."),
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
