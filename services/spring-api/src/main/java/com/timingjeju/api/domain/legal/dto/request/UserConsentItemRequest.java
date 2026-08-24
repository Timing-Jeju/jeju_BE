package com.timingjeju.api.domain.legal.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.LegalProfileException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class UserConsentItemRequest {

  private UUID documentId;
  private Boolean agreed;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = false, format = "uuid")
  public UUID getDocumentId() {
    return documentId;
  }

  @JsonSetter("documentId")
  public void setDocumentId(Object value) {
    if (!(value instanceof String text)) {
      throw LegalProfileException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) {
        throw LegalProfileException.invalidRequest();
      }
      documentId = parsed;
    } catch (IllegalArgumentException failure) {
      throw LegalProfileException.invalidRequest();
    }
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = false)
  public Boolean getAgreed() {
    return agreed;
  }

  @JsonSetter("agreed")
  public void setAgreed(Object value) {
    if (!(value instanceof Boolean decision)) {
      throw LegalProfileException.invalidRequest();
    }
    agreed = decision;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw LegalProfileException.invalidRequest();
  }

  ConsentDecision toDecision() {
    if (documentId == null || agreed == null) {
      throw LegalProfileException.invalidRequest();
    }
    return new ConsentDecision(documentId, agreed);
  }
}
