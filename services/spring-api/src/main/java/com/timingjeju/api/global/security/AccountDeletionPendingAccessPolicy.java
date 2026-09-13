package com.timingjeju.api.global.security;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

public final class AccountDeletionPendingAccessPolicy {
  private final AccountDeletionPendingAccess pendingAccess;

  public AccountDeletionPendingAccessPolicy(AccountDeletionPendingAccess pendingAccess) {
    this.pendingAccess = java.util.Objects.requireNonNull(pendingAccess);
  }

  public boolean mayAccess(Authentication authentication, HttpServletRequest request) {
    if (isDeletionReplay(request) || isStatusRead(request)) {
      return true;
    }
    if (!(authentication instanceof CurrentUserAuthentication current)) {
      return false;
    }
    try {
      return !pendingAccess.isPending(current.getPrincipal().userId());
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static boolean isDeletionReplay(HttpServletRequest request) {
    return "DELETE".equals(request.getMethod()) && "/api/v1/me".equals(request.getRequestURI());
  }

  private static boolean isStatusRead(HttpServletRequest request) {
    return "GET".equals(request.getMethod())
        && request.getRequestURI().startsWith("/api/v1/account-deletion-requests/");
  }
}
