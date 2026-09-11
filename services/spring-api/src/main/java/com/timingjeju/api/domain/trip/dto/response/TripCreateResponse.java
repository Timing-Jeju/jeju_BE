package com.timingjeju.api.domain.trip.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 완료 receipt의 원본 응답 호환성을 문서화하며 새 응답 생성에는 사용하지 않는다. */
@Schema(
    name = "TripCreateResponse",
    description =
        "새 생성은 TripDetail, 배포 전 완료 receipt의 24시간 TTL 내 replay만 TripDetailLegacyV1 또는 TripDetailLegacyV11이다. 최신 값은 Location의 GET으로 조회한다.",
    oneOf = {
      TripAggregateResponse.class,
      TripDetailLegacyV11Response.class,
      TripDetailLegacyV1Response.class
    })
public final class TripCreateResponse {
  private TripCreateResponse() {}
}
