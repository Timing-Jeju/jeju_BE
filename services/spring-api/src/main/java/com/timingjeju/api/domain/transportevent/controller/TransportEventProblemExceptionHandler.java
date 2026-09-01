package com.timingjeju.api.domain.transportevent.controller;

import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.transportevent.TransportEventException;
import com.timingjeju.api.domain.transportevent.exception.TransportEventProblemDefinitions;
import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TransportEventController.class)
public final class TransportEventProblemExceptionHandler {
  private final ProblemResponseWriter writer;
  private final TransportEventProblemDefinitions definitions;

  public TransportEventProblemExceptionHandler(
      ProblemResponseWriter writer, TransportEventProblemDefinitions definitions) {
    this.writer = writer;
    this.definitions = definitions;
  }

  @ExceptionHandler(TransportEventException.class)
  void handle(
      TransportEventException failure, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find(failure.code()), List.of());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MissingServletRequestParameterException.class
  })
  void handleMalformed(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(request, response, definitions.find("INVALID_REQUEST"), List.of());
  }

  @ExceptionHandler(ProfileProvisioningException.class)
  void handleProvisioning(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    writer.write(
        request, response, definitions.find("TRANSPORT_EVENT_DATA_UNAVAILABLE"), List.of());
  }
}
