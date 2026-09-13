package com.timingjeju.api.domain.accountdeletion.worker.adapter;

import com.timingjeju.api.domain.accountdeletion.worker.AuthSubject;

@FunctionalInterface
public interface SupabaseAuthAdminGateway {

  ExternalDeletionResult deleteUser(AuthSubject subject);
}
