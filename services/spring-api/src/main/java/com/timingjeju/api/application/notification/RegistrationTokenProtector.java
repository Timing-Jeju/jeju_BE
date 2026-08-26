package com.timingjeju.api.application.notification;

public interface RegistrationTokenProtector {

  /**
   * @throws RegistrationTokenProtectionFailure when the provider cannot protect the token
   */
  ProtectedRegistrationToken protect(String registrationToken)
      throws RegistrationTokenProtectionFailure;

  /**
   * @throws RegistrationTokenProtectionFailure when the provider cannot recover the token
   */
  String reveal(String ciphertext) throws RegistrationTokenProtectionFailure;
}
