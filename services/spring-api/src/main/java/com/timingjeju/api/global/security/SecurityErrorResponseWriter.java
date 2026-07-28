package com.timingjeju.api.global.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

public final class SecurityErrorResponseWriter {

  private final ObjectMapper objectMapper;

  public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(status);
    response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    String traceId = UUID.randomUUID().toString().replace("-", "");
    objectMapper.writeValue(
        response.getWriter(), new SecurityErrorResponse(code, message, traceId));
  }
}
