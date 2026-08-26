package com.timingjeju.api.application.notification;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class RegistrationTokenPolicy {

  public static final int MAX_PLAINTEXT_BYTES = 4096;
  public static final int MAX_CIPHERTEXT_CHARS = 5500;
  public static final String ASCII_PATTERN = "^[!-~]{1,4096}$";
  private static final Pattern PRINTABLE_ASCII = Pattern.compile(ASCII_PATTERN);

  private RegistrationTokenPolicy() {}

  public static void requireValid(String value) {
    if (value == null
        || !PRINTABLE_ASCII.matcher(value).matches()
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_PLAINTEXT_BYTES) {
      throw PushNotificationException.invalidRequest();
    }
  }
}
