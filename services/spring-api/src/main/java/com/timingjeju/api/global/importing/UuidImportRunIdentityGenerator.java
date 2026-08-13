package com.timingjeju.api.global.importing;

import com.timingjeju.api.application.importing.ImportRunIdentityGenerator;
import java.util.UUID;

public final class UuidImportRunIdentityGenerator implements ImportRunIdentityGenerator {
  @Override
  public UUID newRunId() {
    return UUID.randomUUID();
  }

  @Override
  public UUID newOwnerToken() {
    return UUID.randomUUID();
  }
}
