package com.timingjeju.api.domain.accountdeletion.security;

import java.util.Map;
import javax.crypto.SecretKey;

public record AccountDeletionKeySet(String activeVersion, Map<String, SecretKey> keys) {
  public AccountDeletionKeySet {
    keys = Map.copyOf(keys);
  }
}
