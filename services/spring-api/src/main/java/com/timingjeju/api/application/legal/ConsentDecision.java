package com.timingjeju.api.application.legal;

import java.util.Objects;
import java.util.UUID;

public record ConsentDecision(UUID documentId, boolean agreed) {

  public ConsentDecision {
    Objects.requireNonNull(documentId, "documentId must not be null");
  }
}
