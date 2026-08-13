package com.timingjeju.api.application.importing;

import java.util.UUID;

public interface ImportRunIdentityGenerator {
  UUID newRunId();

  UUID newOwnerToken();
}
