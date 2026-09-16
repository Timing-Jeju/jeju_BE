package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface ProfileImageDeletion {

  void deletePrefix(String objectPrefix);

  default void deletePrefix(String objectPrefix, Runnable leaseCheckpoint) {
    leaseCheckpoint.run();
    deletePrefix(objectPrefix);
  }
}
