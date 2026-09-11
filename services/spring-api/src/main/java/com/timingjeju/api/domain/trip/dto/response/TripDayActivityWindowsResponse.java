package com.timingjeju.api.domain.trip.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** 과거 Day 저장 완료 receipt의 원본 shape를 보존하는 문서 전용 계약이다. */
@Schema(
    name = "TripDayActivityWindowsResponse",
    description =
        "새 저장은 TripDetail, 배포 전 완료 receipt의 24시간 TTL내 replay만 TripDetailLegacyV11이다. 최신 값은 여행 GET으로 조회한다.",
    oneOf = {TripAggregateResponse.class, TripDetailLegacyV11Response.class})
public final class TripDayActivityWindowsResponse {
  private TripDayActivityWindowsResponse() {}
}
