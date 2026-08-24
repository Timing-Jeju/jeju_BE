package com.timingjeju.api.domain.legal.dto.response;

import com.timingjeju.api.application.legal.ConsentUpdateResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UserConsentsResponse(boolean requiredConsentsSatisfied, Instant updatedAt) {

  public static UserConsentsResponse from(ConsentUpdateResult result) {
    return new UserConsentsResponse(result.requiredConsentsSatisfied(), result.updatedAt());
  }
}
