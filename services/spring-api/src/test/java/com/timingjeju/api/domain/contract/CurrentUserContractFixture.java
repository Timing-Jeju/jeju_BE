package com.timingjeju.api.domain.contract;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import java.util.UUID;

public final class CurrentUserContractFixture {

  private final CurrentUserAccessor currentUserAccessor;

  public CurrentUserContractFixture(CurrentUserAccessor currentUserAccessor) {
    this.currentUserAccessor = currentUserAccessor;
  }

  public UUID currentUserId() {
    return currentUserAccessor.getRequired().userId();
  }
}
