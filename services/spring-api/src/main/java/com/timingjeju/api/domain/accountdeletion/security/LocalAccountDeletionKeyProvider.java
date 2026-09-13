package com.timingjeju.api.domain.accountdeletion.security;

import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

public final class LocalAccountDeletionKeyProvider implements AccountDeletionKeyProvider {
  private final String version;
  private final String encodedKey;

  public LocalAccountDeletionKeyProvider(String version, String encodedKey) {
    this.version = version;
    this.encodedKey = encodedKey;
  }

  @Override
  public AccountDeletionKeySet load() {
    try {
      byte[] material = Base64.getDecoder().decode(encodedKey);
      if (material.length != 32) throw new IllegalArgumentException();
      return new AccountDeletionKeySet(
          version, Map.of(version, new SecretKeySpec(material, "AES")));
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("회원 탈퇴 local/test key 구성이 유효하지 않습니다.");
    }
  }
}
