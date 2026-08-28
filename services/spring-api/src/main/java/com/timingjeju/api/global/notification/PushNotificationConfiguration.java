package com.timingjeju.api.global.notification;

import com.timingjeju.api.application.notification.NotificationPreferenceStore;
import com.timingjeju.api.application.notification.PushDeviceStore;
import com.timingjeju.api.application.notification.PushEligibilityStore;
import com.timingjeju.api.application.notification.RegistrationTokenProtector;
import com.timingjeju.api.application.notification.service.NotificationPreferenceService;
import com.timingjeju.api.application.notification.service.PushDeviceService;
import com.timingjeju.api.application.notification.service.PushNotificationEligibilityService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PushNotificationConfiguration {

  @Bean
  RegistrationTokenProtector registrationTokenProtector(
      @Value("${app.notifications.token-encryption-key}") String encodedKey) {
    byte[] key;
    try {
      key = Base64.getDecoder().decode(encodedKey);
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("푸시 token 암호화 키 구성이 유효하지 않습니다.");
    }
    if (key.length != 32) {
      throw new IllegalStateException("푸시 token 암호화 키 구성이 유효하지 않습니다.");
    }
    return new AesGcmRegistrationTokenProtector(new SecretKeySpec(key, "AES"), new SecureRandom());
  }

  @Bean
  PushDeviceService pushDeviceService(
      PushDeviceStore devices, RegistrationTokenProtector tokens, Clock clock) {
    return new PushDeviceService(devices, tokens, clock);
  }

  @Bean
  NotificationPreferenceService notificationPreferenceService(
      NotificationPreferenceStore preferences, Clock clock) {
    return new NotificationPreferenceService(preferences, clock);
  }

  @Bean
  PushNotificationEligibilityService pushNotificationEligibilityService(
      PushEligibilityStore eligibility, RegistrationTokenProtector tokens, Clock clock) {
    return new PushNotificationEligibilityService(eligibility, tokens, clock);
  }
}
