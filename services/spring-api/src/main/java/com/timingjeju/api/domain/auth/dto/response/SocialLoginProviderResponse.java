package com.timingjeju.api.domain.auth.dto.response;

import com.timingjeju.api.domain.auth.service.SocialLoginProvider;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SocialLoginProviderResponse(String id, String displayName) {

  public static SocialLoginProviderResponse from(SocialLoginProvider provider) {
    return new SocialLoginProviderResponse(provider.id(), provider.displayName());
  }
}
