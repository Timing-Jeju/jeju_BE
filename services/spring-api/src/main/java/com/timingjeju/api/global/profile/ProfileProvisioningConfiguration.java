package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.AuthIdentityReader;
import com.timingjeju.api.application.profile.CurrentUserProfileStore;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileProvisioningStore;
import com.timingjeju.api.application.profile.service.CurrentUserProfileService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProfileProvisioningConfiguration {

  @Bean
  CurrentUserProvisioningService currentUserProvisioningService(
      AuthIdentityReader identityReader, ProfileProvisioningStore store, Clock clock) {
    return new CurrentUserProvisioningService(identityReader, store, clock);
  }

  @Bean
  CurrentUserProfileService currentUserProfileService(
      CurrentUserProvisioningService provisioning, CurrentUserProfileStore profiles, Clock clock) {
    return new CurrentUserProfileService(provisioning, profiles, clock);
  }
}
