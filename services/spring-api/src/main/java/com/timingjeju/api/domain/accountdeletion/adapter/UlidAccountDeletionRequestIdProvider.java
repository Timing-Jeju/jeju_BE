package com.timingjeju.api.domain.accountdeletion.adapter;

import com.timingjeju.api.domain.accountdeletion.port.AccountDeletionRequestIdProvider;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;

public final class UlidAccountDeletionRequestIdProvider
    implements AccountDeletionRequestIdProvider {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private final Clock clock;
  private final SecureRandom random;

  public UlidAccountDeletionRequestIdProvider(Clock clock, SecureRandom random) {
    this.clock = clock;
    this.random = random;
  }

  @Override
  public String generate() {
    byte[] bytes = new byte[16];
    long milliseconds = clock.millis();
    for (int index = 5; index >= 0; index--) {
      bytes[index] = (byte) milliseconds;
      milliseconds >>>= 8;
    }
    byte[] randomBytes = new byte[10];
    random.nextBytes(randomBytes);
    System.arraycopy(randomBytes, 0, bytes, 6, randomBytes.length);
    BigInteger value = new BigInteger(1, bytes);
    char[] encoded = new char[26];
    for (int index = encoded.length - 1; index >= 0; index--) {
      encoded[index] = ALPHABET[value.and(BigInteger.valueOf(31)).intValue()];
      value = value.shiftRight(5);
    }
    return new String(encoded);
  }
}
