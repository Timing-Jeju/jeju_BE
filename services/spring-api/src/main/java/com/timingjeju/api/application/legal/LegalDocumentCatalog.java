package com.timingjeju.api.application.legal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record LegalDocumentCatalog(Instant evaluatedAt, String locale, List<LegalDocument> items) {

  public LegalDocumentCatalog {
    Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    Objects.requireNonNull(locale, "locale must not be null");
    items = List.copyOf(items);
  }
}
