package com.timingjeju.api.global.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ApiProblemDetails", description = "Timing Jeju 공개 API 공통 오류 응답")
public record ApiProblemDetails(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uri") String type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uri-reference") String instance,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^[0-9a-f]{32}$",
            description = "서버가 요청 단위로 생성한 추적 식별자")
        String traceId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FieldErrorDetail> fieldErrors) {

  public ApiProblemDetails {
    fieldErrors = List.copyOf(fieldErrors);
  }
}
