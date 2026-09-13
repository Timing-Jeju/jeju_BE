package com.timingjeju.api.domain.accountdeletion.worker.adapter;

@FunctionalInterface
public interface ProfileImageStorageGateway {

  ExternalDeletionResult deletePrefix(String prefix);
}
