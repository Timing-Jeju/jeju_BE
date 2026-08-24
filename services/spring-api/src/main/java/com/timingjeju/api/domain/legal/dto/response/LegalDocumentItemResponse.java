package com.timingjeju.api.domain.legal.dto.response;

import com.timingjeju.api.application.legal.LegalDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LegalDocumentItemResponse(
    UUID documentId,
    @Schema(allowableValues = {"terms", "privacy", "location"}) String type,
    @Schema(pattern = "^(?:[0-9]+\\.[0-9]+\\.[0-9]+|[0-9]{4}-[0-9]{2}-[0-9]{2}\\.v[1-9][0-9]*)$")
        String version,
    @Schema(minLength = 1, maxLength = 100) String title,
    @Schema(minLength = 1, maxLength = 2048) String contentUrl,
    boolean required,
    Instant effectiveAt) {

  public static LegalDocumentItemResponse from(LegalDocument document) {
    return new LegalDocumentItemResponse(
        document.documentId(),
        document.type(),
        document.version(),
        document.title(),
        document.contentUrl(),
        document.required(),
        document.effectiveAt());
  }
}
