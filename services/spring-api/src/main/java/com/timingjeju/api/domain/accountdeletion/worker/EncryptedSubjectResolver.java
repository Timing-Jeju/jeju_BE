package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface EncryptedSubjectResolver {

  AuthSubject resolve(String requestId, EncryptedAuthSubject encryptedSubject);
}
