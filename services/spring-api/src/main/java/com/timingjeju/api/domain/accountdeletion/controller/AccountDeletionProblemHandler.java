package com.timingjeju.api.domain.accountdeletion.controller;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionException;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AccountDeletionController.class)
public final class AccountDeletionProblemHandler {
  private final ProblemResponseWriter writer;
  private final AccountDeletionProblemDefinitions definitions;

  public AccountDeletionProblemHandler(
      ProblemResponseWriter writer, AccountDeletionProblemDefinitions definitions) {
    this.writer = writer;
    this.definitions = definitions;
  }

  @ExceptionHandler(AccountDeletionException.class)
  void handle(
      AccountDeletionException exception, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find(exception.code()), java.util.List.of());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class
  })
  void malformed(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String code =
        "GET".equals(request.getMethod())
            ? "INVALID_DELETION_STATUS_TOKEN"
            : "INVALID_PROFILE_LEGAL_REQUEST";
    writer.write(request, response, definitions.find(code), java.util.List.of());
  }
}
