package com.timingjeju.api.application.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue #61/#106 account-deletion owner가 탈퇴 접수 transaction에서 호출할 additive boundary. 이 port는 삭제
 * command/status를 소유하지 않고 푸시 eligibility 차단만 소유한다.
 */
public interface PushNotificationWithdrawalBoundary {

  void onWithdrawalRequested(UUID userId, Instant requestedAt);
}
