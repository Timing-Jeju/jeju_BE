package com.timingjeju.api.application.security;

import java.util.Optional;

public interface CurrentUserAccessor {

  Optional<CurrentUser> getOptional();

  default CurrentUser getRequired() {
    return getOptional().orElseThrow(() -> new IllegalStateException("인증된 현재 사용자를 찾을 수 없습니다."));
  }
}
