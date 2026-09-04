package com.timingjeju.api.application.commandinput.cleanup;

public interface CommandLocationCleanupPort {
  CommandLocationCleanupResult execute(CommandLocationCleanupCommand command);
}
