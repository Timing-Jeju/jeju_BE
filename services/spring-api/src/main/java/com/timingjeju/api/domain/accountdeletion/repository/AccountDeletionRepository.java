package com.timingjeju.api.domain.accountdeletion.repository;

import com.timingjeju.api.domain.accountdeletion.model.AccountDeletionRecord;
import java.util.Optional;
import java.util.UUID;

public interface AccountDeletionRepository {
  Optional<AccountDeletionRecord> findForReplay(UUID profileId, byte[] idempotencyHash);

  void insert(AccountDeletionRecord record);

  Optional<AccountDeletionRecord> findByTokenHash(byte[] statusTokenHash);
}
