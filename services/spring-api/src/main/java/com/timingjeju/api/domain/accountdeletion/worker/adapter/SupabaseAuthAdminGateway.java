package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;

@FunctionalInterface
public interface SupabaseAuthAdminGateway {

  ExternalDeletionResult deleteUser(AuthSubject subject);

  default ExternalDeletionResult deleteUser(AuthSubject subject, Runnable leaseCheckpoint) {
    leaseCheckpoint.run();
    return deleteUser(subject);
  }
}
