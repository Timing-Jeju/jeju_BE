package com.timingjeju.api.domain.accountdeletion.model;

public final class AccountDeletionException extends RuntimeException {
  private final String code;

  public AccountDeletionException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
