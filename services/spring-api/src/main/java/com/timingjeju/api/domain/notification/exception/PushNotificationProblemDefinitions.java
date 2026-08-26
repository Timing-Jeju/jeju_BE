package com.timingjeju.api.domain.notification.exception;

import com.timingjeju.api.global.error.ProblemDefinition;
import com.timingjeju.api.global.error.ProblemDefinitionContributor;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class PushNotificationProblemDefinitions implements ProblemDefinitionContributor {

  @Override
  public List<ProblemDefinition> definitions() {
    return List.of(
        ProblemDefinition.forCode(
            "INVALID_PUSH_NOTIFICATION_REQUEST", "푸시 알림 요청 오류", 400, "푸시 알림 요청 값이 올바르지 않습니다."),
        ProblemDefinition.forCode(
            "PUSH_NOTIFICATION_DATA_UNAVAILABLE",
            "푸시 알림 데이터 조회 불가",
            503,
            "푸시 알림 데이터를 처리할 수 없습니다."));
  }
}
