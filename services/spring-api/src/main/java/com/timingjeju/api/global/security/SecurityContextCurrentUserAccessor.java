package com.timingjeju.api.global.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityContextCurrentUserAccessor implements CurrentUserAccessor {

  @Override
  public CurrentUser getRequired() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
      throw new AuthenticationCredentialsNotFoundException("인증된 현재 사용자를 찾을 수 없습니다.");
    }
    return currentUser;
  }
}
