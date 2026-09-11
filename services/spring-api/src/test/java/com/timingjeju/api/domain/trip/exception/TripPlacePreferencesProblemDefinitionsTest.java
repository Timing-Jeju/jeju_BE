package com.timingjeju.api.domain.trip.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.places.exception.PlacesProblemDefinitions;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPlacePreferencesProblemDefinitionsTest {
  @Test
  void trips_공용과_place_preferences_전용_동일_code의_fixture문구를_각각_보존한다() {
    Map<String, String[]> trips =
        Map.of(
            "INVALID_REQUEST",
                new String[] {"요청 값이 올바르지 않습니다", "여행 제목, 날짜, timezone과 교통 우선순위를 확인해 주세요."},
            "TRIP_VERSION_CONFLICT",
                new String[] {"여행이 이미 변경되었습니다", "최신 여행과 ETag를 조회한 뒤 다시 수정해 주세요."},
            "TRIP_TERMINAL_STATE_CONFLICT",
                new String[] {"종료된 여행은 변경할 수 없습니다", "완료, 취소 또는 실패한 여행은 이 API로 변경할 수 없습니다."});
    Map<String, String[]> preferences =
        Map.of(
            "INVALID_REQUEST", new String[] {"요청 값이 올바르지 않습니다", "필수값, 형식과 If-Match를 확인해 주세요."},
            "PLACE_NOT_FOUND", new String[] {"장소를 찾을 수 없습니다", "요청한 장소가 없거나 사용할 수 없습니다."},
            "TRIP_VERSION_CONFLICT",
                new String[] {"여행 조건이 이미 변경되었습니다", "최신 여행과 ETag를 조회한 뒤 다시 요청해 주세요."},
            "TRIP_TERMINAL_STATE_CONFLICT",
                new String[] {"종료된 여행은 변경할 수 없습니다", "완료, 취소 또는 실패한 여행 조건은 변경할 수 없습니다."});

    var shared = new TripProblemDefinitions().definitions();
    var dedicated = new TripPlacePreferencesProblemDefinitions();
    trips.forEach(
        (code, text) -> {
          var definition =
              shared.stream()
                  .filter(candidate -> candidate.code().equals(code))
                  .findFirst()
                  .orElseThrow();
          assertThat(definition.title()).isEqualTo(text[0]);
          assertThat(definition.detail()).isEqualTo(text[1]);
        });
    preferences.forEach(
        (code, text) -> {
          var definition = dedicated.find(code);
          assertThat(definition.title()).isEqualTo(text[0]);
          assertThat(definition.detail()).isEqualTo(text[1]);
        });

    var publicPlaces = new PlacesProblemDefinitions().definitions();
    var publicPlaceNotFound =
        publicPlaces.stream()
            .filter(candidate -> candidate.code().equals("PLACE_NOT_FOUND"))
            .findFirst()
            .orElseThrow();
    assertThat(publicPlaceNotFound.title()).isEqualTo("장소를 찾을 수 없습니다");
    assertThat(publicPlaceNotFound.detail()).isEqualTo("장소가 없거나 공개할 수 없습니다.");
  }
}
