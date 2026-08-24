package com.timingjeju.api.application.legal;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface LegalDocumentStore {

  List<LegalDocument> findEffectiveCandidates(String locale, Instant evaluatedAt);
}
