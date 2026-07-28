package com.timingjeju.api.domain.auth.dto.response;

import com.timingjeju.api.domain.auth.service.SocialLoginProvider;

public record SocialLoginProviderResponse(String id, String displayName) {

  public static SocialLoginProviderResponse from(SocialLoginProvider provider) {
    return new SocialLoginProviderResponse(provider.id(), provider.displayName());
  }
}
