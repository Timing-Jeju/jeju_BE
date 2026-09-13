package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface SupabaseAuthAdminDeletion {

  void deleteUser(AuthSubject subject);

  default void deleteUser(AuthSubject subject, Runnable leaseCheckpoint) {
    leaseCheckpoint.run();
    deleteUser(subject);
  }
}
