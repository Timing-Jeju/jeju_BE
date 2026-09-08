package com.timingjeju.api.domain.transportevent.controller;

import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.transportevent.TransportEventException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class TransportEventRequestBoundary {
  private static final Pattern CANONICAL_LENGTH = Pattern.compile("^(?:0|[1-9][0-9]*)$");

  private TransportEventRequestBoundary() {}

  static byte[] readRequiredJson(HttpServletRequest request) {
    rejectTransferEncoding(request);
    requireJson(request);
    Long declared = declaredLength(request, IdempotencyRequest.MAX_BODY_BYTES);
    long servletLength = request.getContentLengthLong();
    if (servletLength < -1 || servletLength > IdempotencyRequest.MAX_BODY_BYTES) invalid();
    if (declared != null && (servletLength < 0 || declared.longValue() != servletLength)) invalid();
    try {
      byte[] body = request.getInputStream().readNBytes(IdempotencyRequest.MAX_BODY_BYTES + 1);
      if (body.length == 0
          || body.length > IdempotencyRequest.MAX_BODY_BYTES
          || (servletLength >= 0 && servletLength != body.length)
          || (declared != null && declared.longValue() != body.length)) {
        invalid();
      }
      return body;
    } catch (IOException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  static void requireEmptyDelete(HttpServletRequest request) {
    rejectTransferEncoding(request);
    Long declared = declaredLength(request, 0);
    long servletLength = request.getContentLengthLong();
    if (servletLength < -1 || servletLength > 0 || (declared != null && servletLength < 0)) {
      invalid();
    }
    try {
      if (request.getInputStream().read() != -1) invalid();
    } catch (IOException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static void requireJson(HttpServletRequest request) {
    try {
      String raw = request.getContentType();
      if (raw == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(raw))) {
        invalid();
      }
    } catch (IllegalArgumentException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static Long declaredLength(HttpServletRequest request, long maximum) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    if (values == null || !values.hasMoreElements()) return null;
    String raw = values.nextElement();
    if (values.hasMoreElements() || raw == null || !CANONICAL_LENGTH.matcher(raw).matches()) {
      invalid();
    }
    try {
      long parsed = Long.parseLong(raw);
      if (parsed > maximum) invalid();
      return parsed;
    } catch (NumberFormatException failure) {
      throw TransportEventException.invalidRequest();
    }
  }

  private static void rejectTransferEncoding(HttpServletRequest request) {
    Enumeration<String> values = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    if (values != null && values.hasMoreElements()) invalid();
  }

  private static void invalid() {
    throw TransportEventException.invalidRequest();
  }
}
