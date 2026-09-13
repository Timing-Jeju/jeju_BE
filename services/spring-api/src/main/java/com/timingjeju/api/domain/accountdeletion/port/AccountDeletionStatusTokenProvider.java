package com.timingjeju.api.domain.accountdeletion.port;

@FunctionalInterface
public interface AccountDeletionStatusTokenProvider {
  String generate();
}
