package com.timingjeju.api.domain.savedplaces.dto;

import com.timingjeju.api.global.error.ProblemDefinition;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class SavedPlacesProblemDefinitions {
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem("INVALID_REQUEST", "요청 값이 올바르지 않습니다", 400, "관심 장소 요청 값을 확인해 주세요."),
        problem("INVALID_QUERY_PARAMETER", "조회 조건이 올바르지 않습니다", 400, "관심 장소 조회 조건을 확인해 주세요."),
        problem("INVALID_CURSOR", "커서가 올바르지 않습니다", 400, "처음부터 다시 조회해 주세요."),
        problem(
            "CURSOR_CONTEXT_MISMATCH", "커서의 조회 조건이 현재 요청과 다릅니다", 400, "변경한 조건으로 처음부터 다시 조회해 주세요."),
        problem("PLACE_NOT_FOUND", "장소를 찾을 수 없습니다", 404, "저장하려는 장소 정보가 없습니다."),
        problem(
            "IDEMPOTENCY_PAYLOAD_CONFLICT",
            "같은 멱등성 키의 요청 내용이 다릅니다",
            409,
            "새 Idempotency-Key로 다시 요청해 주세요."),
        problem(
            "SAVED_PLACE_ALREADY_EXISTS",
            "이미 다른 내용으로 저장한 장소입니다",
            409,
            "현재 ETag를 확인한 뒤 PATCH로 수정해 주세요."),
        problem(
            "SAVED_PLACE_VERSION_CONFLICT",
            "관심 장소가 이미 변경되었습니다",
            409,
            "최신 관심 장소를 조회한 뒤 다시 수정해 주세요."),
        problem("SAVED_PLACE_NOT_FOUND", "관심 장소를 찾을 수 없습니다", 404, "요청한 관심 장소가 없거나 접근할 수 없습니다."),
        problem(
            "SAVED_PLACE_CONSTRAINT_VIOLATION",
            "관심 장소 값을 처리할 수 없습니다",
            422,
            "메모, 태그, 우선순위 또는 희망 Day 값을 확인해 주세요."));
  }

  public ProblemDefinition find(String code) {
    return definitions().stream()
        .filter(definition -> definition.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown saved places problem code"));
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
