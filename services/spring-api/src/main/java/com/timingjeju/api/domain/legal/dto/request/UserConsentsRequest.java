package com.timingjeju.api.domain.legal.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.LegalProfileException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public final class UserConsentsRequest {

  private List<UserConsentItemRequest> consents;

  @ArraySchema(minItems = 1, maxItems = 20)
  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = false)
  public List<UserConsentItemRequest> getConsents() {
    return consents;
  }

  @JsonSetter("consents")
  public void setConsents(Object value) {
    if (!(value instanceof List<?> list)) {
      throw LegalProfileException.invalidRequest();
    }
    try {
      consents =
          list.stream()
              .map(
                  item -> {
                    if (!(item instanceof java.util.Map<?, ?> map)) {
                      throw LegalProfileException.invalidRequest();
                    }
                    UserConsentItemRequest request = new UserConsentItemRequest();
                    for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                      if (!(entry.getKey() instanceof String key)) {
                        throw LegalProfileException.invalidRequest();
                      }
                      switch (key) {
                        case "documentId" -> request.setDocumentId(entry.getValue());
                        case "agreed" -> request.setAgreed(entry.getValue());
                        default -> request.rejectUnknown(key, entry.getValue());
                      }
                    }
                    return request;
                  })
              .toList();
    } catch (LegalProfileException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw LegalProfileException.invalidRequest();
    }
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw LegalProfileException.invalidRequest();
  }

  public List<ConsentDecision> toDecisions() {
    if (consents == null) {
      throw LegalProfileException.invalidRequest();
    }
    return consents.stream().map(UserConsentItemRequest::toDecision).toList();
  }
}
