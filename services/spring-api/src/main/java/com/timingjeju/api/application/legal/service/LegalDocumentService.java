package com.timingjeju.api.application.legal.service;

import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalDocumentCatalog;
import com.timingjeju.api.application.legal.LegalDocumentSelection;
import com.timingjeju.api.application.legal.LegalDocumentStore;
import com.timingjeju.api.application.legal.LegalProfileException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class LegalDocumentService {

  public static final String SUPPORTED_LOCALE = "ko-KR";

  private final LegalDocumentStore documents;
  private final Clock clock;

  public LegalDocumentService(LegalDocumentStore documents, Clock clock) {
    this.documents = Objects.requireNonNull(documents, "documents must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public LegalDocumentCatalog read(String locale) {
    String requestedLocale = locale == null ? SUPPORTED_LOCALE : locale;
    if (!SUPPORTED_LOCALE.equals(requestedLocale)) {
      throw LegalProfileException.invalidRequest();
    }
    Instant evaluatedAt = clock.instant();
    List<LegalDocument> selected =
        LegalDocumentSelection.latest(
            documents.findEffectiveCandidates(requestedLocale, evaluatedAt), requestedLocale);
    return new LegalDocumentCatalog(evaluatedAt, requestedLocale, selected);
  }
}
