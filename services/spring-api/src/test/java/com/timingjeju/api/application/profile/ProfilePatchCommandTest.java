package com.timingjeju.api.application.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProfilePatchCommandTest {

  @Test
  void nickname은_unicode_code_point_50자까지_trim해_허용한다() {
    ProfilePatchCommand command =
        new ProfilePatchCommand(true, "  " + "😀".repeat(50) + "  ", false, null);

    assertThat(command.nickname()).isEqualTo("😀".repeat(50));
    assertThat(command.localePresent()).isFalse();
  }

  @Test
  void nickname_51자_blank_control문자는_거부한다() {
    assertInvalid(() -> new ProfilePatchCommand(true, "가".repeat(51), false, null));
    assertInvalid(() -> new ProfilePatchCommand(true, " \t ", false, null));
    assertInvalid(() -> new ProfilePatchCommand(true, "\t제주여행", false, null));
    assertInvalid(() -> new ProfilePatchCommand(true, "제주\u0000여행", false, null));
  }

  @Test
  void locale은_정확한_canonical_allowlist만_허용한다() {
    assertThat(new ProfilePatchCommand(false, null, true, "ko-KR").locale()).isEqualTo("ko-KR");
    assertInvalid(() -> new ProfilePatchCommand(false, null, true, "ko-kr"));
    assertInvalid(() -> new ProfilePatchCommand(false, null, true, " ko-KR "));
  }

  @Test
  void omitted는_보존하지만_empty_patch와_explicit_null은_거부한다() {
    assertInvalid(() -> new ProfilePatchCommand(false, null, false, null));
    assertInvalid(() -> new ProfilePatchCommand(true, null, false, null));
    assertInvalid(() -> new ProfilePatchCommand(false, null, true, null));
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(CurrentUserProfileException.class)
        .extracting(failure -> ((CurrentUserProfileException) failure).code())
        .isEqualTo("INVALID_PROFILE_LEGAL_REQUEST");
  }
}
