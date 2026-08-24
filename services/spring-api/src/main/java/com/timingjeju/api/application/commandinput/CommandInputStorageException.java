package com.timingjeju.api.application.commandinput;

public final class CommandInputStorageException extends RuntimeException {
  public CommandInputStorageException(String stableCode) {
    super(stableCode, null, false, false);
  }
}
