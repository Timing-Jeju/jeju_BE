package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AccountRequestDenier;
import com.timingjeju.api.domain.accountdeletion.worker.AppOwnedDataErasure;
import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.GlobalSessionRevoker;

public final class DisabledAccountDeletionOperations
    implements GlobalSessionRevoker, AccountRequestDenier, AppOwnedDataErasure {

  @Override
  public void revokeAll(AuthSubject subject) {
    throw disabled();
  }

  @Override
  public void denyFurtherRequests(AuthSubject subject) {
    throw disabled();
  }

  @Override
  public void deleteAndAnonymize(
      com.timingjeju.api.domain.accountdeletion.worker.DeletionLease lease, AuthSubject subject) {
    throw disabled();
  }

  private static DeletionOperationException disabled() {
    return DeletionOperationException.terminal("ACCOUNT_DELETION_EXTERNAL_OPERATIONS_DISABLED");
  }
}
