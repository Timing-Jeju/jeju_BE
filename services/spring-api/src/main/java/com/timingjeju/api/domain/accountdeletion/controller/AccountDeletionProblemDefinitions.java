package com.timingjeju.api.domain.accountdeletion.controller;

import com.timingjeju.api.global.error.ProblemDefinition;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class AccountDeletionProblemDefinitions {
  public List<ProblemDefinition> definitions() {
    return List.of(
        problem(
            "IDEMPOTENCY_KEY_INVALID",
            "멱등성 키가 올바르지 않습니다",
            400,
            "1~128자 printable ASCII 값을 입력해 주세요."),
        problem(
            "INVALID_PROFILE_LEGAL_REQUEST",
            "탈퇴 요청 값이 올바르지 않습니다",
            400,
            "요청 형식과 확인 문구를 다시 확인해 주세요."),
        problem("RECENT_REAUTHENTICATION_REQUIRED", "최근 로그인이 필요합니다", 428, "다시 로그인한 뒤 탈퇴를 요청해 주세요."),
        problem(
            "IDEMPOTENCY_PAYLOAD_CONFLICT",
            "같은 멱등성 키의 요청 내용이 다릅니다",
            409,
            "새 Idempotency-Key로 다시 요청해 주세요."),
        problem(
            "ACCOUNT_DELETION_ALREADY_TERMINAL", "이미 완료된 탈퇴 요청입니다", 409, "기존 탈퇴 요청 상태를 확인해 주세요."),
        problem(
            "DELETION_STATUS_TOKEN_EXPIRED", "탈퇴 상태 token이 만료되었습니다", 410, "상태 조회 지원 기간이 종료되었습니다."),
        problem(
            "INVALID_DELETION_STATUS_TOKEN",
            "탈퇴 상태 token이 올바르지 않습니다",
            401,
            "발급받은 status token을 확인해 주세요."),
        problem(
            "DELETION_STATUS_FORBIDDEN",
            "탈퇴 상태 요청에 접근할 수 없습니다",
            403,
            "status token과 요청 ID를 확인해 주세요."),
        problem("PROFILE_RESOURCE_NOT_FOUND", "탈퇴 요청을 찾을 수 없습니다", 404, "요청 ID를 확인해 주세요."),
        problem(
            "ACCOUNT_DELETION_SECRET_UNAVAILABLE",
            "탈퇴 요청 비밀정보를 처리할 수 없습니다",
            500,
            "잠시 후 다시 시도해 주세요."));
  }

  public ProblemDefinition find(String code) {
    return definitions().stream()
        .filter(definition -> definition.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown account deletion problem code"));
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
