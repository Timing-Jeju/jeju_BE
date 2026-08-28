package com.timingjeju.api.global.error;

import com.timingjeju.api.global.logging.RequestTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

public final class ProblemResponseWriter {

  private static final String PROBLEM_INSTANCE_PREFIX = "urn:timing-jeju:problem:";
  private static final Comparator<FieldErrorDetail> FIELD_ERROR_ORDER =
      Comparator.comparing(FieldErrorDetail::field).thenComparing(FieldErrorDetail::detail);
  private static final List<String> PRESERVED_CORS_HEADERS =
      List.of(
          "Vary",
          "Access-Control-Allow-Origin",
          "Access-Control-Expose-Headers",
          "Access-Control-Allow-Credentials",
          "Access-Control-Allow-Private-Network");

  private final ObjectMapper objectMapper;
  private final ProblemCodeRegistry registry;
  private final RequestTraceId requestTraceId;

  public ProblemResponseWriter(
      ObjectMapper objectMapper, ProblemCodeRegistry registry, RequestTraceId requestTraceId) {
    this.objectMapper = objectMapper;
    this.registry = registry;
    this.requestTraceId = requestTraceId;
  }

  public boolean write(HttpServletRequest request, HttpServletResponse response, String problemCode)
      throws IOException {
    return write(request, response, problemCode, List.of());
  }

  public boolean write(
      HttpServletRequest request,
      HttpServletResponse response,
      String problemCode,
      List<FieldErrorDetail> fieldErrors)
      throws IOException {
    return write(request, response, registry.find(problemCode), fieldErrors);
  }

  public boolean write(
      HttpServletRequest request,
      HttpServletResponse response,
      ProblemDefinition definition,
      List<FieldErrorDetail> fieldErrors)
      throws IOException {
    if (response.isCommitted()) {
      return false;
    }
    String traceId = requestTraceId.getOrCreate(request);
    List<FieldErrorDetail> sortedFieldErrors =
        fieldErrors.stream().sorted(FIELD_ERROR_ORDER).toList();
    ApiProblemDetails problem =
        new ApiProblemDetails(
            definition.type().toString(),
            definition.title(),
            definition.status(),
            definition.detail(),
            instance(traceId),
            definition.code(),
            traceId,
            sortedFieldErrors);
    byte[] responseBody = objectMapper.writeValueAsBytes(problem);
    Map<String, List<String>> preservedHeaders = preservedHeaders(response);

    response.reset();
    response.setStatus(definition.status());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(RequestTraceId.TRACE_ID_HEADER, traceId);
    preservedHeaders.forEach(
        (name, values) -> {
          response.setHeader(name, values.getFirst());
          values.stream().skip(1).forEach(value -> response.addHeader(name, value));
        });
    response.getOutputStream().write(responseBody);
    return true;
  }

  private static String instance(String traceId) {
    return PROBLEM_INSTANCE_PREFIX + traceId;
  }

  private static Map<String, List<String>> preservedHeaders(HttpServletResponse response) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    PRESERVED_CORS_HEADERS.forEach(
        name -> {
          List<String> values = List.copyOf(response.getHeaders(name));
          if (!values.isEmpty()) {
            headers.put(name, values);
          }
        });
    return headers;
  }
}
