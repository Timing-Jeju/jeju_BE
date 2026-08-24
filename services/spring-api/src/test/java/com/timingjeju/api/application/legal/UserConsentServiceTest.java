package com.timingjeju.api.application.legal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.timingjeju.api.application.legal.service.UserConsentService;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UserConsentServiceTest {

  private static final UUID USER_ID = UUID.fromString("19000000-0000-0000-0000-000000000001");
  private static final UUID DOCUMENT_ID = UUID.fromString("19200000-0000-0000-0000-000000000001");

  @Test
  void update는_duplicate_document를_profile_provisioning전에_거부한다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    UserConsentStore store = mock(UserConsentStore.class);
    UserConsentService service = new UserConsentService(provisioning, store, fixedClock());

    assertThatThrownBy(
            () ->
                service.update(
                    currentUser(),
                    List.of(
                        new ConsentDecision(DOCUMENT_ID, true),
                        new ConsentDecision(DOCUMENT_ID, true))))
        .isInstanceOf(LegalProfileException.class)
        .extracting("code")
        .isEqualTo("INVALID_PROFILE_LEGAL_REQUEST");
    verify(provisioning, never()).provision(any());
    verify(store, never()).updateRequiredConsents(any(), any(), any(), any());
  }

  @Test
  void update는_canonical_sub를_provision한뒤_같은_sub만_store에_전달한다() {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    UserConsentStore store = mock(UserConsentStore.class);
    UserConsentService service = new UserConsentService(provisioning, store, fixedClock());

    service.update(currentUser(), List.of(new ConsentDecision(DOCUMENT_ID, true)));

    verify(provisioning).provision(currentUser());
    verify(store)
        .updateRequiredConsents(
            USER_ID,
            "ko-KR",
            List.of(new ConsentDecision(DOCUMENT_ID, true)),
            Instant.parse("2026-08-25T00:00:00Z"));
  }

  private static CurrentUser currentUser() {
    return new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);
  }

  private static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC);
  }
}
