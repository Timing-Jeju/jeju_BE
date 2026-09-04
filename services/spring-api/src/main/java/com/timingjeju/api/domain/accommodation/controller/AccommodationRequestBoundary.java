package com.timingjeju.api.domain.accommodation.controller;

import com.timingjeju.api.application.accommodation.AccommodationException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

final class AccommodationRequestBoundary {
  static final int MAX_BODY_BYTES = 1_048_576;
  private static final Pattern CANONICAL_LENGTH = Pattern.compile("^(?:0|[1-9][0-9]*)$");

  private AccommodationRequestBoundary() {}

  static String requiredSingleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) throw AccommodationException.invalidRequest();
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null || value.isEmpty()) {
      throw AccommodationException.invalidRequest();
    }
    return value;
  }

  static String requiredPrintableAsciiHeader(HttpServletRequest request, String name) {
    String value = requiredSingleHeader(request, name);
    if (value.length() > 128
        || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
      throw AccommodationException.invalidRequest();
    }
    return value;
  }

  static void requireNoQuery(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw AccommodationException.invalidRequest();
  }

  static byte[] readRequiredBody(HttpServletRequest request) {
    rejectTransferEncoding(request);
    Long declared = declaredContentLength(request);
    long servletLength = request.getContentLengthLong();
    if (servletLength < -1 || servletLength > MAX_BODY_BYTES) {
      throw AccommodationException.invalidRequest();
    }
    if (declared != null && (servletLength < 0 || declared.longValue() != servletLength)) {
      throw AccommodationException.invalidRequest();
    }
    try {
      byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (body.length == 0
          || body.length > MAX_BODY_BYTES
          || (servletLength >= 0 && servletLength != body.length)
          || (declared != null && declared.longValue() != body.length)) {
        throw AccommodationException.invalidRequest();
      }
      return body;
    } catch (IOException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  static void requireEmptyDelete(HttpServletRequest request) {
    requireNoQuery(request);
    rejectTransferEncoding(request);
    Enumeration<String> values = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    boolean declared = values != null && values.hasMoreElements();
    if (declared) {
      if (!"0".equals(values.nextElement()) || values.hasMoreElements()) {
        throw AccommodationException.invalidRequest();
      }
    }
    long servletLength = request.getContentLengthLong();
    if (servletLength < -1 || servletLength > 0 || (declared && servletLength < 0)) {
      throw AccommodationException.invalidRequest();
    }
    try {
      if (request.getInputStream().read() != -1) throw AccommodationException.invalidRequest();
    } catch (IOException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static Long declaredContentLength(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null || !CANONICAL_LENGTH.matcher(value).matches()) {
      throw AccommodationException.invalidRequest();
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed > MAX_BODY_BYTES) throw AccommodationException.invalidRequest();
      return parsed;
    } catch (NumberFormatException failure) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static void rejectTransferEncoding(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    if (values != null && values.hasMoreElements()) throw AccommodationException.invalidRequest();
  }
}
