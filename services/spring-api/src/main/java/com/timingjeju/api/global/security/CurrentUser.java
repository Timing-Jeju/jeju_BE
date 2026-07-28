package com.timingjeju.api.global.security;

import java.util.UUID;

public record CurrentUser(UUID userId, AuthenticatedRole role, UUID sessionId) {

  public CurrentUser {
    if (userId == null) {
      throw new IllegalArgumentException("현재 사용자 ID는 필수입니다.");
    }
    if (role == null) {
      throw new IllegalArgumentException("현재 사용자 역할은 필수입니다.");
    }
  }
}
