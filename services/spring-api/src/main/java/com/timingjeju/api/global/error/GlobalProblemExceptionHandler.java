package com.timingjeju.api.global.error;

import com.timingjeju.api.application.pagination.CursorInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public final class GlobalProblemExceptionHandler {

  private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  private final ProblemResponseWriter responseWriter;
  private final AuthenticationProblemWriter authenticationProblemWriter;
  private final ValidationErrorMapper validationErrorMapper;

  public GlobalProblemExceptionHandler(
      ProblemResponseWriter responseWriter,
      AuthenticationProblemWriter authenticationProblemWriter,
      ValidationErrorMapper validationErrorMapper) {
    this.responseWriter = responseWriter;
    this.authenticationProblemWriter = authenticationProblemWriter;
    this.validationErrorMapper = validationErrorMapper;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  void handleBodyValidation(
      MethodArgumentNotValidException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    List<FieldErrorDetail> fieldErrors =
        Stream.concat(
                exception.getBindingResult().getFieldErrors().stream()
                    .map(validationErrorMapper::map),
                exception.getBindingResult().getGlobalErrors().stream()
                    .map(validationErrorMapper::map))
            .toList();
    responseWriter.write(request, response, VALIDATION_FAILED, fieldErrors);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  void handleMethodValidation(
      HandlerMethodValidationException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    if (exception.isForReturnValue()) {
      responseWriter.write(request, response, StandardProblemCode.INTERNAL_SERVER_ERROR.name());
      return;
    }
    Stream<FieldErrorDetail> parameterErrors =
        exception.getParameterValidationResults().stream()
            .flatMap(
                result -> {
                  String parameterName = result.getMethodParameter().getParameterName();
                  String field = indexedField(parameterName, result.getContainerIndex());
                  return result.getResolvableErrors().stream()
                      .map(error -> validationErrorMapper.map(field, error));
                });
    Stream<FieldErrorDetail> crossParameterErrors =
        exception.getCrossParameterValidationResults().stream()
            .map(error -> validationErrorMapper.map("request", error));
    List<FieldErrorDetail> fieldErrors =
        Stream.concat(parameterErrors, crossParameterErrors).toList();
    responseWriter.write(request, response, VALIDATION_FAILED, fieldErrors);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  void handleMissingRequestHeader(
      MissingRequestHeaderException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    responseWriter.write(
        request,
        response,
        VALIDATION_FAILED,
        List.of(new FieldErrorDetail(exception.getHeaderName(), "필수 요청 헤더입니다.")));
  }

  @ExceptionHandler(MissingRequestCookieException.class)
  void handleMissingRequestCookie(
      MissingRequestCookieException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    responseWriter.write(
        request,
        response,
        VALIDATION_FAILED,
        List.of(new FieldErrorDetail(exception.getCookieName(), "필수 요청 쿠키입니다.")));
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  void handleMissingRequestPart(
      MissingServletRequestPartException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    responseWriter.write(
        request,
        response,
        VALIDATION_FAILED,
        List.of(new FieldErrorDetail(exception.getRequestPartName(), "필수 요청 파트입니다.")));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  void handleMissingRequestParameter(
      MissingServletRequestParameterException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    responseWriter.write(
        request,
        response,
        VALIDATION_FAILED,
        List.of(new FieldErrorDetail(exception.getParameterName(), "필수 요청 파라미터입니다.")));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  void handleConstraintViolation(
      ConstraintViolationException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    List<FieldErrorDetail> fieldErrors =
        exception.getConstraintViolations().stream().map(validationErrorMapper::map).toList();
    responseWriter.write(request, response, VALIDATION_FAILED, fieldErrors);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  void handleTypeMismatch(
      MethodArgumentTypeMismatchException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    responseWriter.write(
        request,
        response,
        VALIDATION_FAILED,
        List.of(new FieldErrorDetail(exception.getName(), "올바른 형식의 값을 입력해 주세요.")));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  void handleUnreadableMessage(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, VALIDATION_FAILED);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  void handleNoResource(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, StandardProblemCode.RESOURCE_NOT_FOUND.name());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  void handleMethodNotSupported(
      HttpRequestMethodNotSupportedException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writeWithHeaders(
        request,
        response,
        StandardProblemCode.METHOD_NOT_ALLOWED.name(),
        exception.getHeaders(),
        HttpHeaders.ALLOW);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  void handleMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writeWithHeaders(
        request,
        response,
        StandardProblemCode.NOT_ACCEPTABLE.name(),
        exception.getHeaders(),
        HttpHeaders.ACCEPT);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  void handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writeWithHeaders(
        request,
        response,
        StandardProblemCode.UNSUPPORTED_MEDIA_TYPE.name(),
        exception.getHeaders(),
        HttpHeaders.ACCEPT,
        HttpHeaders.ACCEPT_PATCH);
  }

  @ExceptionHandler(ResponseStatusException.class)
  void handleResponseStatus(
      ResponseStatusException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (exception.getStatusCode().value() == 401) {
      authenticationProblemWriter.writeCanonical(request, response);
      return;
    }
    responseWriter.write(request, response, codeForStatus(exception.getStatusCode().value()));
  }

  @ExceptionHandler(ApiProblemException.class)
  void handleApiProblem(
      ApiProblemException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, exception.code());
  }

  @ExceptionHandler(CursorInvalidException.class)
  void handleCursorInvalid(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, CursorInvalidException.PROBLEM_CODE);
  }

  @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
  void propagateSecurityException(RuntimeException exception) {
    throw exception;
  }

  @ExceptionHandler(Exception.class)
  void handleUnexpected(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    responseWriter.write(request, response, StandardProblemCode.INTERNAL_SERVER_ERROR.name());
  }

  private void writeWithHeaders(
      HttpServletRequest request,
      HttpServletResponse response,
      String code,
      HttpHeaders sourceHeaders,
      String... allowedHeaderNames)
      throws IOException {
    if (!responseWriter.write(request, response, code)) {
      return;
    }
    Arrays.stream(allowedHeaderNames)
        .forEach(
            headerName -> {
              List<String> values = sourceHeaders.get(headerName);
              if (values == null || values.isEmpty()) {
                return;
              }
              response.setHeader(headerName, values.getFirst());
              values.stream().skip(1).forEach(value -> response.addHeader(headerName, value));
            });
  }

  private static String indexedField(String field, Integer containerIndex) {
    return field == null || containerIndex == null || containerIndex < 0
        ? field
        : field + "[" + containerIndex + "]";
  }

  private static String codeForStatus(int status) {
    return switch (status) {
      case 400 -> StandardProblemCode.VALIDATION_FAILED.name();
      case 403 -> StandardProblemCode.AUTH_ACCESS_DENIED.name();
      case 404 -> StandardProblemCode.RESOURCE_NOT_FOUND.name();
      case 405 -> StandardProblemCode.METHOD_NOT_ALLOWED.name();
      case 406 -> StandardProblemCode.NOT_ACCEPTABLE.name();
      case 409 -> StandardProblemCode.CONFLICT.name();
      case 415 -> StandardProblemCode.UNSUPPORTED_MEDIA_TYPE.name();
      case 422 -> StandardProblemCode.UNPROCESSABLE_ENTITY.name();
      case 424 -> StandardProblemCode.FAILED_DEPENDENCY.name();
      case 429 -> StandardProblemCode.TOO_MANY_REQUESTS.name();
      case 502 -> StandardProblemCode.UPSTREAM_ERROR.name();
      case 503 -> StandardProblemCode.SERVICE_UNAVAILABLE.name();
      case 504 -> StandardProblemCode.UPSTREAM_TIMEOUT.name();
      default -> StandardProblemCode.INTERNAL_SERVER_ERROR.name();
    };
  }
}
