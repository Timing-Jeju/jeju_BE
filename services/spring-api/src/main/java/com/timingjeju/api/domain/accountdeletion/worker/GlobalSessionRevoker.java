package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface GlobalSessionRevoker {

  /**
   * Historical compatibility name. Implementations verify the Spring deletion-pending deny gate;
   * they must not pretend Supabase supports subject-only global logout.
   */
  void revokeAll(AuthSubject subject);
}
