package com.timingjeju.api.domain.savedplaces.controller;

import com.timingjeju.api.application.pagination.CursorContextMismatchException;
import com.timingjeju.api.application.pagination.CursorInvalidException;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlacesProblemDefinitions;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = SavedPlacesController.class)
public final class SavedPlacesProblemHandler {
  private final ProblemResponseWriter writer;
  private final SavedPlacesProblemDefinitions definitions;

  public SavedPlacesProblemHandler(
      ProblemResponseWriter writer, SavedPlacesProblemDefinitions definitions) {
    this.writer = writer;
    this.definitions = definitions;
  }

  @ExceptionHandler(SavedPlaceException.class)
  void handle(
      SavedPlaceException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find(exception.code()), java.util.List.of());
  }

  @ExceptionHandler(CursorContextMismatchException.class)
  void handleCursorContext(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(
        request, response, definitions.find("CURSOR_CONTEXT_MISMATCH"), java.util.List.of());
  }

  @ExceptionHandler(CursorInvalidException.class)
  void handleCursor(HttpServletRequest request, HttpServletResponse response) throws IOException {
    writer.write(request, response, definitions.find("INVALID_CURSOR"), java.util.List.of());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class,
    HandlerMethodValidationException.class
  })
  void handleMalformed(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String code =
        "GET".equals(request.getMethod())
                && "/api/v1/me/saved-places".equals(request.getRequestURI())
            ? "INVALID_QUERY_PARAMETER"
            : "INVALID_REQUEST";
    writer.write(request, response, definitions.find(code), java.util.List.of());
  }
}
