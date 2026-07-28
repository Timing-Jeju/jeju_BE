package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import java.util.Map;

public final class NaverUserInfoService {

  private final NaverUserInfoGateway gateway;
  private final NaverUserInfoAdmissionService admissionService;

  public NaverUserInfoService(NaverUserInfoGateway gateway) {
    this(gateway, NaverUserInfoAdmissionService.production());
  }

  public NaverUserInfoService(
      NaverUserInfoGateway gateway, NaverUserInfoAdmissionService admissionService) {
    this.gateway = gateway;
    this.admissionService = admissionService;
  }

  public NaverStandardUserInfo getUserInfo(String providerAccessToken) {
    return admissionService.execute(() -> mapUserInfo(gateway.getUserInfo(providerAccessToken)));
  }

  private static NaverStandardUserInfo mapUserInfo(Map<String, Object> payload) {
    if (payload == null) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
    }
    if (!"00".equals(payload.get("resultcode")) || !"success".equals(payload.get("message"))) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
    }
    Object response = payload.get("response");
    if (!(response instanceof Map<?, ?> profile)) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
    }
    String sub = requiredText(profile, "id", NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
    String email = requiredText(profile, "email", NaverUserInfoFailureCode.EMAIL_REQUIRED);
    return new NaverStandardUserInfo(
        sub,
        email,
        optionalText(profile, "name"),
        optionalText(profile, "nickname"),
        optionalText(profile, "profile_image"));
  }

  private static String requiredText(
      Map<?, ?> profile, String field, NaverUserInfoFailureCode failureCode) {
    String value = optionalText(profile, field);
    if (value == null) {
      throw new NaverUserInfoException(failureCode);
    }
    return value;
  }

  private static String optionalText(Map<?, ?> profile, String field) {
    Object value = profile.get(field);
    if (!(value instanceof String text)) {
      return null;
    }
    String normalized = text.trim();
    return normalized.isEmpty() ? null : normalized;
  }
}
