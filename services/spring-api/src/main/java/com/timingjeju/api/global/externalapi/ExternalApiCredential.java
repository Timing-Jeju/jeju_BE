package com.timingjeju.api.global.externalapi;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ExternalApiCredential {

  private static final char[] UPPERCASE_HEX = "0123456789ABCDEF".toCharArray();

  private final ExternalApiProvider provider;
  private final String decodedValue;

  private ExternalApiCredential(ExternalApiProvider provider, String decodedValue) {
    this.provider = Objects.requireNonNull(provider, "provider는 필수입니다.");
    this.decodedValue = Objects.requireNonNull(decodedValue, "key는 필수입니다.");
    validateInput(provider, decodedValue);
  }

  public static ExternalApiCredential from(
      ExternalApiProvider provider, String decodedOrHeaderValue) {
    return new ExternalApiCredential(provider, decodedOrHeaderValue);
  }

  public ExternalApiCredentialPlacement placement() {
    return provider.credentialPlacement();
  }

  public String encodedQueryValue() {
    requirePlacement(
        ExternalApiCredentialPlacement.QUERY_SERVICE_KEY, provider + " key는 header로만 전달해야 합니다.");
    byte[] utf8 = decodedValue.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(utf8.length);
    for (byte item : utf8) {
      int value = item & 0xff;
      if (isUnreserved(value)) {
        encoded.append((char) value);
      } else {
        encoded.append('%').append(UPPERCASE_HEX[value >>> 4]).append(UPPERCASE_HEX[value & 0x0f]);
      }
    }
    return encoded.toString();
  }

  public String headerValue() {
    requirePlacement(
        ExternalApiCredentialPlacement.HEADER_API_KEY,
        provider + " key는 serviceKey query로만 전달해야 합니다.");
    return decodedValue;
  }

  static void validateInput(ExternalApiProvider provider, String value) {
    if (provider.credentialPlacement() == ExternalApiCredentialPlacement.QUERY_SERVICE_KEY
        && containsPercentEncodedTriplet(value)) {
      throw new IllegalArgumentException(
          provider.environmentName("API_KEY")
              + "는 decoded 원문 key여야 하며 percent-encoded 값을 허용하지 않습니다.");
    }
  }

  private void requirePlacement(ExternalApiCredentialPlacement expected, String failureMessage) {
    if (placement() != expected) {
      throw new IllegalStateException(failureMessage);
    }
  }

  private static boolean containsPercentEncodedTriplet(String value) {
    for (int index = 0; index + 2 < value.length(); index++) {
      if (value.charAt(index) == '%'
          && isAsciiHex(value.charAt(index + 1))
          && isAsciiHex(value.charAt(index + 2))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAsciiHex(char value) {
    return (value >= '0' && value <= '9')
        || (value >= 'a' && value <= 'f')
        || (value >= 'A' && value <= 'F');
  }

  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }

  @Override
  public String toString() {
    return "ExternalApiCredential[provider="
        + provider
        + ", placement="
        + placement()
        + ", value=[REDACTED]]";
  }
}
