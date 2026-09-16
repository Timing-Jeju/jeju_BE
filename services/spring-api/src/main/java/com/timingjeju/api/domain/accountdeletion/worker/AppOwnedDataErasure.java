package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface AppOwnedDataErasure {

  void deleteAndAnonymize(DeletionLease lease, AuthSubject subject);
}
