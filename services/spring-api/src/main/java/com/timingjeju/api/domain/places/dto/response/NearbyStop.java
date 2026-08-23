package com.timingjeju.api.domain.places.dto.response;

import com.timingjeju.api.domain.places.model.NearbyStopProjectionContract;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 상세 응답의 주변 정류장 항목. 최대 5개이며 expiresAt은 link/stop의 effective expiry다. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record NearbyStop(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID stopId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stopName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long distanceMeters,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer walkMinutes,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"spatial_radius", "fixture", "manual", "api_nearby"})
        String linkMethod,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128)
        String provider,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale) {

  public NearbyStop {
    stopId = Objects.requireNonNull(stopId, "stopId는 필수입니다.");
    linkMethod = NearbyStopProjectionContract.requireLinkMethod(linkMethod);
    provider = NearbyStopProjectionContract.requireProvider(provider);
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
  }
}
