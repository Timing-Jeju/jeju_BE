package com.timingjeju.api.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Naver custom OAuth adapter가 Supabase Auth에 반환하는 표준 UserInfo 문서 계약이다. */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record NaverUserInfoResponse(
    String sub, String email, String name, String preferred_username, String picture) {}
