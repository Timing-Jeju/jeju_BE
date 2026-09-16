package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.SupabaseAuthAdminDeletion;
import java.util.Objects;

public final class SupabaseAuthAdminDeletionAdapter implements SupabaseAuthAdminDeletion {

  private final SupabaseAuthAdminGateway gateway;

  public SupabaseAuthAdminDeletionAdapter(SupabaseAuthAdminGateway gateway) {
    this.gateway = Objects.requireNonNull(gateway, "gateway는 필수입니다.");
  }

  @Override
  public void deleteUser(AuthSubject subject) {
    deleteUser(subject, () -> {});
  }

  @Override
  public void deleteUser(AuthSubject subject, Runnable leaseCheckpoint) {
    ExternalDeletionResult result = gateway.deleteUser(subject, leaseCheckpoint);
    if (result == null) {
      throw DeletionOperationException.retryable("AUTH_ADMIN_DELETE_UNAVAILABLE");
    }
  }
}
