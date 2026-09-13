package com.timingjeju.api.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("unit")
class AccountDeletionPendingAccessPolicyTest {
  private static final UUID USER_ID = UUID.fromString("46d9a0ca-3472-4f7e-b1b8-b751da5a7f40");

  @Test
  void pending계정은_보호_API가_차단되지만_DELETE_replay와_status_GET은_유지된다() {
    AccountDeletionPendingAccess pending = userId -> true;
    var policy = new AccountDeletionPendingAccessPolicy(pending);
    var authentication =
        new CurrentUserAuthentication(
            new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null));

    assertThat(policy.mayAccess(authentication, request("POST", "/api/v1/push/devices"))).isFalse();
    assertThat(policy.mayAccess(authentication, request("GET", "/api/v1/me"))).isFalse();
    assertThat(policy.mayAccess(authentication, request("DELETE", "/api/v1/me"))).isTrue();
    assertThat(
            policy.mayAccess(
                authentication, request("GET", "/api/v1/account-deletion-requests/token")))
        .isTrue();
  }

  @Test
  void pending조회_실패도_fail_closed하고_일반계정은_통과한다() {
    var authentication =
        new CurrentUserAuthentication(
            new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null));
    assertThat(
            new AccountDeletionPendingAccessPolicy(userId -> false)
                .mayAccess(authentication, request("POST", "/api/v1/trips")))
        .isTrue();
    assertThat(
            new AccountDeletionPendingAccessPolicy(
                    userId -> {
                      throw new IllegalStateException();
                    })
                .mayAccess(authentication, request("POST", "/api/v1/trips")))
        .isFalse();
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }
}
