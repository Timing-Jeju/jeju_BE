package com.timingjeju.api.domain.savedplaces.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 새 저장과 기존 완료 receipt의 원본 응답을 구분하는 문서 전용 계약이다. */
@Schema(
    name = "SavedPlaceCreateResponse",
    description =
        "새 응답은 etag를 포함한다. 배포 전 24시간 TTL내 완료 receipt replay만 etag 없는 원본을 그대로 반환한다. 최신 버전은 목록 GET으로 조회한다.",
    oneOf = {SavedPlaceResponse.class, SavedPlaceLegacyV1Response.class})
public final class SavedPlaceCreateResponse {
  private SavedPlaceCreateResponse() {}
}
