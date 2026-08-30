package com.timingjeju.api.global.push.firebase;

import com.timingjeju.api.application.push.PushMessageSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FcmProperties.class)
public class FirebasePushConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "app.push.fcm",
      name = "enabled",
      havingValue = "false",
      matchIfMissing = true)
  PushMessageSender disabledPushMessageSender() {
    return new DisabledPushMessageSender();
  }

  @Bean
  @ConditionalOnMissingBean(FirebaseAdminClientFactory.class)
  FirebaseAdminClientFactory firebaseAdminClientFactory() {
    return new DefaultFirebaseAdminClientFactory();
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.push.fcm", name = "enabled", havingValue = "true")
  PushMessageSender firebasePushMessageSender(
      FcmProperties properties,
      FirebaseAdminClientFactory clientFactory,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ObjectProvider<Clock> clockProvider) {
    Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
    MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
    FcmClientSettings settings = properties.requiredClientSettings();
    FirebaseMessagingGateway gateway;
    try {
      gateway = clientFactory.create(settings, clock);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("FCM ADC 또는 secret mount 자격 증명을 초기화할 수 없습니다.");
    }
    return new FirebasePushMessageSender(
        gateway,
        new FirebaseMessageMapper(clock),
        new FirebaseErrorClassifier(),
        new FirebasePushTelemetry(meterRegistry, clock));
  }
}
