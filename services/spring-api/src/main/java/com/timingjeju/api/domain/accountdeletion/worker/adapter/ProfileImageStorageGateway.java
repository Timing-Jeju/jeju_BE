package com.timingjeju.api.domain.accountdeletion.worker.adapter;

@FunctionalInterface
public interface ProfileImageStorageGateway {

  ExternalDeletionResult deletePrefix(String prefix);

  default ExternalDeletionResult deletePrefix(String prefix, Runnable leaseCheckpoint) {
    leaseCheckpoint.run();
    return deletePrefix(prefix);
  }
}
