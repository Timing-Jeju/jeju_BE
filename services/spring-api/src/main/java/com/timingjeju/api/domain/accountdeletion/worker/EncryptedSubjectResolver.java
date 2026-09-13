package com.timingjeju.api.domain.accountdeletion.worker;

import java.util.UUID;

@FunctionalInterface
public interface EncryptedSubjectResolver {

  AuthSubject resolve(UUID requestId, EncryptedAuthSubject encryptedSubject);
}
