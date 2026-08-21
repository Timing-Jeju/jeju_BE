package com.timingjeju.api.domain.places.exception;

import com.timingjeju.api.domain.places.controller.PlacesController;
import com.timingjeju.api.domain.places.dto.request.PlaceQueryValidationException;
import com.timingjeju.api.global.error.FieldErrorDetail;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PlacesController.class)
public class PlacesProblemExceptionHandler {

  private final ProblemResponseWriter writer;

  public PlacesProblemExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @ExceptionHandler(PlaceQueryValidationException.class)
  void handleQuery(
      PlaceQueryValidationException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(PlaceListException.class)
  void handleList(
      PlaceListException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, exception.code());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  void handleType(
      MethodArgumentTypeMismatchException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(
        request,
        response,
        "INVALID_QUERY_PARAMETER",
        List.of(new FieldErrorDetail(exception.getName(), "올바른 형식의 값을 입력해 주세요.")));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  void handleMethodValidation(
      HandlerMethodValidationException exception,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    writer.write(request, response, "INVALID_QUERY_PARAMETER");
  }
}
