package com.timingjeju.api.domain.accountdeletion.port;

@FunctionalInterface
public interface AccountDeletionRequestIdProvider {
  String generate();
}
