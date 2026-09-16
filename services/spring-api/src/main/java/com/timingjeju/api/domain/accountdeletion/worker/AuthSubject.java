package com.timingjeju.api.domain.accountdeletion.worker;

public final class AuthSubject {

  private final String value;

  private AuthSubject(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Auth subject는 비어 있을 수 없습니다.");
    }
    this.value = value;
  }

  public static AuthSubject of(String value) {
    return new AuthSubject(value);
  }

  public String value() {
    return value;
  }

  public String profileImagePrefix() {
    return "profile-images/" + value;
  }

  @Override
  public String toString() {
    return "AuthSubject[<redacted>]";
  }
}
