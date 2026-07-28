package com.timingjeju.api.global.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

public final class CurrentUserAuthentication extends AbstractAuthenticationToken {

  private final CurrentUser currentUser;

  public CurrentUserAuthentication(CurrentUser currentUser) {
    super(List.of());
    this.currentUser = currentUser;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public CurrentUser getPrincipal() {
    return currentUser;
  }

  @Override
  public String getName() {
    return currentUser.userId().toString();
  }
}
