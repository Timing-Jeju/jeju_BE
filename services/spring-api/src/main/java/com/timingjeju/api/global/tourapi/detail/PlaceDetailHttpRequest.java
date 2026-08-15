package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;

record PlaceDetailHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {
  PlaceDetailHttpRequest {
    queryParameters = Map.copyOf(queryParameters);
  }
}
