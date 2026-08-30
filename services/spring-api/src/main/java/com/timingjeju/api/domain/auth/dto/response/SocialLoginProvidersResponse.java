package com.timingjeju.api.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SocialLoginProvidersResponse(List<SocialLoginProviderResponse> providers) {}
