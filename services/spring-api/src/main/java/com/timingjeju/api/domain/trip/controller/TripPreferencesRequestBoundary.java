package com.timingjeju.api.domain.trip.controller;

import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.application.trip.TripException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class TripPreferencesRequestBoundary {
  static final int MAX_BODY_BYTES = 1_048_576;
  private static final Pattern CANONICAL_LENGTH = Pattern.compile("^(?:0|[1-9][0-9]*)$");

  private TripPreferencesRequestBoundary() {}

  static void requireNoQuery(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw TripException.invalidRequest();
  }

  static void requireJsonMediaType(HttpServletRequest request) {
    String raw = request.getContentType();
    if (raw == null) throw TripException.invalidRequest();
    try {
      MediaType actual = MediaType.parseMediaType(raw);
      if (!MediaType.APPLICATION_JSON.isCompatibleWith(actual)) {
        throw TripException.invalidRequest();
      }
    } catch (IllegalArgumentException failure) {
      throw TripException.invalidRequest();
    }
  }

  static String requiredSingleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) throw TripException.invalidRequest();
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null || value.isEmpty() || value.indexOf(',') >= 0) {
      throw TripException.invalidRequest();
    }
    return value;
  }

  static long requiredRevision(HttpServletRequest request, UUID tripId) {
    try {
      var expected = TripEntityTag.parse(requiredSingleHeader(request, HttpHeaders.IF_MATCH));
      if (!tripId.equals(expected.tripId())) {
        throw TripException.invalidRequest();
      }
      return expected.revision();
    } catch (TripException failure) {
      throw TripException.invalidRequest();
    }
  }

  static byte[] readRequiredBody(HttpServletRequest request) {
    rejectTransferEncoding(request);
    Long declared = declaredContentLength(request);
    long reported = request.getContentLengthLong();
    if (reported < -1 || reported > MAX_BODY_BYTES) throw TripException.invalidRequest();
    if (declared != null && (reported < 0 || declared.longValue() != reported)) {
      throw TripException.invalidRequest();
    }
    try {
      byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (body.length == 0
          || body.length > MAX_BODY_BYTES
          || (reported >= 0 && reported != body.length)
          || (declared != null && declared.longValue() != body.length)) {
        throw TripException.invalidRequest();
      }
      return body;
    } catch (IOException failure) {
      throw TripException.invalidRequest();
    }
  }

  private static void rejectTransferEncoding(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    if (values != null && values.hasMoreElements()) throw TripException.invalidRequest();
  }

  private static Long declaredContentLength(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    if (values == null || !values.hasMoreElements()) return null;
    String raw = values.nextElement();
    if (values.hasMoreElements() || raw == null || !CANONICAL_LENGTH.matcher(raw).matches()) {
      throw TripException.invalidRequest();
    }
    try {
      long parsed = Long.parseLong(raw);
      if (parsed > MAX_BODY_BYTES) throw TripException.invalidRequest();
      return parsed;
    } catch (NumberFormatException failure) {
      throw TripException.invalidRequest();
    }
  }
}
