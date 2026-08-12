package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;

record ExternalApiPropertyValues(
    ExternalApiProvider provider,
    boolean enabled,
    String apiKey,
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout)
    implements ExternalApiProviderProperties {}
