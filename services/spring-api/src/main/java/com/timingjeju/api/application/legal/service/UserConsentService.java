package com.timingjeju.api.application.legal.service;

import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.ConsentUpdateResult;
import com.timingjeju.api.application.legal.LegalProfileException;
import com.timingjeju.api.application.legal.UserConsentStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class UserConsentService {

  private final CurrentUserProvisioningService provisioning;
  private final UserConsentStore consents;
  private final Clock clock;

  public UserConsentService(
      CurrentUserProvisioningService provisioning, UserConsentStore consents, Clock clock) {
    this.provisioning = Objects.requireNonNull(provisioning, "provisioning must not be null");
    this.consents = Objects.requireNonNull(consents, "consents must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ConsentUpdateResult update(CurrentUser currentUser, List<ConsentDecision> decisions) {
    Objects.requireNonNull(currentUser, "currentUser must not be null");
    validate(decisions);
    provisioning.provision(currentUser);
    return consents.updateRequiredConsents(
        currentUser.userId(), LegalDocumentService.SUPPORTED_LOCALE, decisions, clock.instant());
  }

  private static void validate(List<ConsentDecision> decisions) {
    if (decisions == null || decisions.isEmpty() || decisions.size() > 20) {
      throw LegalProfileException.invalidRequest();
    }
    Set<UUID> ids = new HashSet<>();
    for (ConsentDecision decision : decisions) {
      if (decision == null || !ids.add(decision.documentId())) {
        throw LegalProfileException.invalidRequest();
      }
    }
  }
}
