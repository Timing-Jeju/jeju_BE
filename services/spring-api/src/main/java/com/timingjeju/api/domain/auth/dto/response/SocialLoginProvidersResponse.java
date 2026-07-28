package com.timingjeju.api.domain.auth.dto.response;

import java.util.List;

public record SocialLoginProvidersResponse(List<SocialLoginProviderResponse> providers) {}
