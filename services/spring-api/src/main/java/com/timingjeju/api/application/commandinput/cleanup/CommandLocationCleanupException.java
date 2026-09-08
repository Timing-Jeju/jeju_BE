package com.timingjeju.api.application.commandinput.cleanup;

public final class CommandLocationCleanupException extends RuntimeException {
  public enum Code {
    COMMAND_LOCATION_CLEANUP_UNAVAILABLE
  }

  private final Code code;

  private CommandLocationCleanupException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public static CommandLocationCleanupException unavailable() {
    return new CommandLocationCleanupException(Code.COMMAND_LOCATION_CLEANUP_UNAVAILABLE);
  }

  public Code code() {
    return code;
  }
}
