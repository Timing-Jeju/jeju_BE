package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.Map;

record DetailImageHttpRequest(
    ExternalApiOperation operation,
    String relativePath,
    Map<String, String> queryParameters,
    ExternalApiResponseFormat format) {}
