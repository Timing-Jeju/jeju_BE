package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;

record TagoRouteHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {}
