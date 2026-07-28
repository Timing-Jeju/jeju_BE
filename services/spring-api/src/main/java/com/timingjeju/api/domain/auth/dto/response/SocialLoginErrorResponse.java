package com.timingjeju.api.domain.auth.dto.response;

public record SocialLoginErrorResponse(String code, String message, String traceId) {}
