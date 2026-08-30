package com.timingjeju.api.application.notification;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PushLocalePolicy {

  public static final int MAX_LENGTH = 35;
  public static final String BCP47_PATTERN =
      "^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-[A-Z]{2}|-[0-9]{3})?"
          + "(?:-[A-Za-z0-9]{5,8}|-[0-9][A-Za-z0-9]{3})*"
          + "(?:-[0-9A-WY-Za-wy-z](?:-[A-Za-z0-9]{2,8})+)*"
          + "(?:-x(?:-[A-Za-z0-9]{1,8})+)?$";
  private static final Pattern SUPPORTED_BCP47 = Pattern.compile(BCP47_PATTERN);

  private PushLocalePolicy() {}

  public static void requireValid(String value) {
    if (value == null || value.length() > MAX_LENGTH || !SUPPORTED_BCP47.matcher(value).matches()) {
      throw PushNotificationException.invalidRequest();
    }
    Locale parsed = Locale.forLanguageTag(value);
    if (parsed.getLanguage().isBlank() || !parsed.toLanguageTag().equals(value)) {
      throw PushNotificationException.invalidRequest();
    }
  }
}
