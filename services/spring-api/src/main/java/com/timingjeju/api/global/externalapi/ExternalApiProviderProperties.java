package com.timingjeju.api.global.externalapi;

import java.net.URI;
import java.time.Duration;

interface ExternalApiProviderProperties {

  ExternalApiProvider provider();

  boolean enabled();

  String apiKey();

  URI baseUrl();

  Duration connectTimeout();

  Duration readTimeout();
}
