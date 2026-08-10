package com.timingjeju.api.application.pagination;

public final class CursorInvalidException extends RuntimeException {

  public static final String PROBLEM_CODE = "CURSOR_INVALID";

  public CursorInvalidException() {
    super(null, null, false, false);
  }
}
