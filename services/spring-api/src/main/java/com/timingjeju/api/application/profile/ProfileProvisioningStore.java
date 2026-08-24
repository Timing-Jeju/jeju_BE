package com.timingjeju.api.application.profile;

@FunctionalInterface
public interface ProfileProvisioningStore {

  ProvisionedCurrentUser provision(ProfileProvisioningRequest request);
}
