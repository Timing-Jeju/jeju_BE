package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface ProfileImageDeletion {

  void deletePrefix(String objectPrefix);
}
