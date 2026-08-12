package com.timingjeju.api.global.externalapi;

import java.util.Locale;

public enum ExternalApiResponseFormat {
  JSON {
    @Override
    boolean supports(String contentType) {
      String mediaType = mediaType(contentType);
      return "application/json".equals(mediaType)
          || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }
  },
  XML {
    @Override
    boolean supports(String contentType) {
      String mediaType = mediaType(contentType);
      return "application/xml".equals(mediaType)
          || "text/xml".equals(mediaType)
          || (mediaType.startsWith("application/") && mediaType.endsWith("+xml"));
    }
  };

  abstract boolean supports(String contentType);

  private static String mediaType(String value) {
    int parameter = value.indexOf(';');
    return (parameter < 0 ? value : value.substring(0, parameter)).trim().toLowerCase(Locale.ROOT);
  }
}
