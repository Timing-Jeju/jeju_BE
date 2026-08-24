package com.timingjeju.api.domain.legal.dto.response;

import com.timingjeju.api.application.legal.LegalDocumentCatalog;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LegalDocumentsResponse(
    Instant evaluatedAt,
    @Schema(allowableValues = "ko-KR") String locale,
    @ArraySchema(maxItems = 20) List<LegalDocumentItemResponse> items) {

  public static LegalDocumentsResponse from(LegalDocumentCatalog catalog) {
    return new LegalDocumentsResponse(
        catalog.evaluatedAt(),
        catalog.locale(),
        catalog.items().stream().map(LegalDocumentItemResponse::from).toList());
  }
}
