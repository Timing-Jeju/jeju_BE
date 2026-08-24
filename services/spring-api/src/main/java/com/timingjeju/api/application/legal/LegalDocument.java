package com.timingjeju.api.application.legal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LegalDocument(
    UUID documentId,
    String type,
    String locale,
    String version,
    String title,
    String contentUrl,
    boolean required,
    Instant effectiveAt) {

  public LegalDocument {
    Objects.requireNonNull(documentId, "documentId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(locale, "locale must not be null");
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(contentUrl, "contentUrl must not be null");
    Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
  }
}
