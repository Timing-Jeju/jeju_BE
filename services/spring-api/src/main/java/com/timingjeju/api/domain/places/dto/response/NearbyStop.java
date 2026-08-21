package com.timingjeju.api.domain.places.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** #66이 실제 조회를 연결하기 전까지 상세 응답은 이 타입의 빈 배열만 반환한다. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record NearbyStop(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID stopId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String stopName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long distanceMeters,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer walkMinutes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String linkMethod,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provider,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale) {}
