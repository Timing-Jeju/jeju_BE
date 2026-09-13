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

  public static GenerationException inputUnavailable() {
    return new GenerationException("GENERATION_INPUT_UNAVAILABLE");
  }

  public static GenerationException intakeUnavailable() {
    return new GenerationException("ASYNC_INTAKE_UNAVAILABLE");
  }

  public static GenerationException quotaExceeded() {
    return new GenerationException("ASYNC_RUN_QUOTA_EXCEEDED");
  }

  public static GenerationException activeRunConflict() {
    return new GenerationException("ACTIVE_RUN_CONFLICT");
  }

  public static GenerationException invalidPath() {
    return new GenerationException("INVALID_PATH_PARAMETER");
  }

  public static GenerationException ifMatchRequired() {
    return new GenerationException("IF_MATCH_REQUIRED");
  }

  public static GenerationException ifMatchInvalid() {
    return new GenerationException("IF_MATCH_INVALID");
  }
}
