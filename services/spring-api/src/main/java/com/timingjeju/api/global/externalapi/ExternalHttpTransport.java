package com.timingjeju.api.global.externalapi;

import java.io.IOException;
import java.time.Duration;

interface ExternalHttpTransport {
  ExternalHttpResponse exchange(
      ExternalHttpRequest request, Duration connectTimeout, Duration responseTimeout)
      throws IOException, InterruptedException;
}
