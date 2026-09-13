package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface AccountDeletionWorkerCommand {

  void pollOnce();
}
