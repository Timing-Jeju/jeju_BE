package com.timingjeju.api.domain.accountdeletion.worker;

@FunctionalInterface
public interface AccountRequestDenier {

  void denyFurtherRequests(AuthSubject subject);
}
