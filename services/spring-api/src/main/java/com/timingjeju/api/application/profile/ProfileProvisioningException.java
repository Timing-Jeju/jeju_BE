package com.timingjeju.api.application.profile;

public final class ProfileProvisioningException extends RuntimeException {

  private final ProfileProvisioningError code;

  private ProfileProvisioningException(ProfileProvisioningError code, String message) {
    super(message);
    this.code = code;
  }

  public static ProfileProvisioningException invalidAuthIdentity() {
    return new ProfileProvisioningException(
        ProfileProvisioningError.INVALID_AUTH_IDENTITY, "인증 identity 계약이 올바르지 않습니다.");
  }

  public static ProfileProvisioningException emailConflict() {
    return new ProfileProvisioningException(
        ProfileProvisioningError.EMAIL_OWNERSHIP_CONFLICT, "이메일을 다른 사용자에게 자동 연결할 수 없습니다.");
  }

  public static ProfileProvisioningException providerSubjectConflict() {
    return new ProfileProvisioningException(
        ProfileProvisioningError.PROVIDER_SUBJECT_CONFLICT, "소셜 provider subject를 자동 연결할 수 없습니다.");
  }

  public static ProfileProvisioningException storageUnavailable() {
    return new ProfileProvisioningException(
        ProfileProvisioningError.STORAGE_UNAVAILABLE, "프로필 저장소를 사용할 수 없습니다.");
  }

  public ProfileProvisioningError code() {
    return code;
  }
}
