package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface GlobalSessionRevoker {

  void revokeAll(AuthSubject subject);
}
