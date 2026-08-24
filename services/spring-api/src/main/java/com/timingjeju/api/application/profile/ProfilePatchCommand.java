package com.timingjeju.api.application.profile;

public record ProfilePatchCommand(
    boolean nicknamePresent, String nickname, boolean localePresent, String locale) {

  private static final int MAX_NICKNAME_CODE_POINTS = 50;

  public ProfilePatchCommand {
    if (!nicknamePresent && !localePresent) {
      throw CurrentUserProfileException.invalidRequest();
    }
    if (nicknamePresent) {
      nickname = normalizeNickname(nickname);
    }
    if (localePresent) {
      locale = normalizeLocale(locale);
    }
  }

  private static String normalizeNickname(String value) {
    if (value == null || containsControl(value)) {
      throw CurrentUserProfileException.invalidRequest();
    }
    String normalized = value.strip();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1 || length > MAX_NICKNAME_CODE_POINTS) {
      throw CurrentUserProfileException.invalidRequest();
    }
    return normalized;
  }

  private static String normalizeLocale(String value) {
    if (value == null || !"ko-KR".equals(value)) {
      throw CurrentUserProfileException.invalidRequest();
    }
    return value;
  }

  private static boolean containsControl(String value) {
    return value
        .codePoints()
        .anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL);
  }
}
