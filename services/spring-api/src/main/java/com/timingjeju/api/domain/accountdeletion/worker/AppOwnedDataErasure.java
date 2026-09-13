package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface AppOwnedDataErasure {

  void deleteAndAnonymize(AuthSubject subject);
}
