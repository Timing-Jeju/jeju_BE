package com.timingjeju.api.application.generation;

public final class GenerationException extends RuntimeException {
  private GenerationException(String code) {
    super(code, null, false, false);
  }

  public static GenerationException invalidRequest() {
    return new GenerationException("INVALID_ASYNC_RUN_REQUEST");
  }

  public static GenerationException inputConstraintViolation() {
    return new GenerationException("GENERATION_INPUT_CONSTRAINT_VIOLATION");
  }

  public String code() {
    return getMessage();
  }
}
