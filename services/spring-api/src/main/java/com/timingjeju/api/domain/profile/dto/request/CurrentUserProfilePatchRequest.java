package com.timingjeju.api.domain.profile.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.timingjeju.api.application.profile.CurrentUserProfileException;
import com.timingjeju.api.application.profile.ProfilePatchCommand;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, minProperties = 1)
public final class CurrentUserProfilePatchRequest {

  private String nickname;
  private boolean nicknamePresent;
  private String locale;
  private boolean localePresent;

  @Schema(minLength = 1, maxLength = 50, nullable = false)
  public String getNickname() {
    return nickname;
  }

  @JsonSetter("nickname")
  public void setNickname(Object nickname) {
    if (!(nickname instanceof String value)) {
      throw CurrentUserProfileException.invalidRequest();
    }
    this.nickname = value;
    this.nicknamePresent = true;
  }

  @Schema(allowableValues = "ko-KR", nullable = false)
  public String getLocale() {
    return locale;
  }

  @JsonSetter("locale")
  public void setLocale(Object locale) {
    if (!(locale instanceof String value)) {
      throw CurrentUserProfileException.invalidRequest();
    }
    this.locale = value;
    this.localePresent = true;
  }

  @JsonAnySetter
  void rejectUnknown(String field, Object value) {
    throw CurrentUserProfileException.invalidRequest();
  }

  public ProfilePatchCommand toCommand() {
    return new ProfilePatchCommand(nicknamePresent, nickname, localePresent, locale);
  }
}
