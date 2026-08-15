package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;

record DetailInfoHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {
  DetailInfoHttpRequest {
    queryParameters = Map.copyOf(queryParameters);
  }
}
