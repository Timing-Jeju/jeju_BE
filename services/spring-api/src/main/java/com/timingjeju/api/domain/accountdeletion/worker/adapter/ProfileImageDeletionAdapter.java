package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.ProfileImageDeletion;
import java.util.Objects;

public final class ProfileImageDeletionAdapter implements ProfileImageDeletion {

  private final ProfileImageStorageGateway gateway;

  public ProfileImageDeletionAdapter(ProfileImageStorageGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway는 필수입니다.");
  }

  @Override
  public void deletePrefix(String objectPrefix) {
    ExternalDeletionResult result = gateway.deletePrefix(objectPrefix);
    if (result == null) {
      throw DeletionOperationException.retryable("PROFILE_IMAGE_DELETE_UNAVAILABLE");
    }
  }
}
