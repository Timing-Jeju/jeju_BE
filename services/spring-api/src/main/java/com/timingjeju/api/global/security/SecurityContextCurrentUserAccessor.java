package com.timingjeju.api.global.security;

import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityContextCurrentUserAccessor implements CurrentUserAccessor {

  @Override
  public Optional<CurrentUser> getOptional() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
            && authentication.getPrincipal() instanceof CurrentUser currentUser
        ? Optional.of(currentUser)
        : Optional.empty();
  }

  @Override
  public CurrentUser getRequired() {
    return getOptional()
        .orElseThrow(
            () -> new AuthenticationCredentialsNotFoundException("인증된 현재 사용자를 찾을 수 없습니다."));
  }
}
