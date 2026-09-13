package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface SupabaseAuthAdminDeletion {

  void deleteUser(AuthSubject subject);
}
