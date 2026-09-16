package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.application.security.AccountDeletionPendingAccess;
import com.timingjeju.api.domain.accountdeletion.worker.AccountRequestDenier;
import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;
import com.timingjeju.api.domain.accountdeletion.worker.DeletionOperationException;
import com.timingjeju.api.domain.accountdeletion.worker.GlobalSessionRevoker;
import java.util.UUID;

/**
 * Backward-compatible implementation of the historical SESSIONS_REVOKED step. Supabase has no
 * subject-only admin logout API, so this verifies the Spring pending-account gate.
 */
public final class JdbcDeletionPendingVerifier
    implements GlobalSessionRevoker, AccountRequestDenier {
  private final AccountDeletionPendingAccess pendingAccess;

  public JdbcDeletionPendingVerifier(AccountDeletionPendingAccess pendingAccess) {
    this.pendingAccess = java.util.Objects.requireNonNull(pendingAccess);
  }

  @Override
  public void revokeAll(AuthSubject subject) {
    verify(subject);
  }

  @Override
  public void denyFurtherRequests(AuthSubject subject) {
    verify(subject);
  }

  private void verify(AuthSubject subject) {
    try {
      UUID userId = UUID.fromString(subject.value());
      if (!userId.toString().equals(subject.value()) || !pendingAccess.isPending(userId)) {
        throw DeletionOperationException.terminal("ACCOUNT_DELETION_DENY_GATE_INACTIVE");
      }
    } catch (DeletionOperationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw DeletionOperationException.terminal("ACCOUNT_DELETION_DENY_GATE_UNAVAILABLE");
    }
  }
}
