package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;

record TagoArrivalHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {}
