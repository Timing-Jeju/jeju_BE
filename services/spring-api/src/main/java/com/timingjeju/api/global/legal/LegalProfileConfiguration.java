package com.timingjeju.api.global.legal;

import com.timingjeju.api.application.legal.LegalDocumentStore;
import com.timingjeju.api.application.legal.UserConsentStore;
import com.timingjeju.api.application.legal.service.LegalDocumentService;
import com.timingjeju.api.application.legal.service.UserConsentService;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LegalProfileConfiguration {

  @Bean
  LegalDocumentService legalDocumentService(LegalDocumentStore documents, Clock clock) {
    return new LegalDocumentService(documents, clock);
  }

  @Bean
  UserConsentService userConsentService(
      CurrentUserProvisioningService provisioning, UserConsentStore consents, Clock clock) {
    return new UserConsentService(provisioning, consents, clock);
  }
}
