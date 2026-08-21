package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.DefaultStayPolicyResolver;
import com.timingjeju.api.application.staypolicy.StayPolicyImportService;
import com.timingjeju.api.application.staypolicy.StayPolicyLookup;
import com.timingjeju.api.application.staypolicy.StayPolicyPublicationStore;
import com.timingjeju.api.application.staypolicy.StayPolicyResolver;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetCatalog;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StayPolicyConfiguration {

  @Bean
  StayPolicyImportService stayPolicyImportService(
      StayPolicyTargetCatalog targetCatalog,
      StayPolicyPublicationStore publicationStore,
      @Qualifier("idempotencyClock") Clock applicationClock) {
    return new StayPolicyImportService(targetCatalog, publicationStore, applicationClock);
  }

  @Bean
  StayPolicyResolver stayPolicyResolver(StayPolicyLookup lookup) {
    return new DefaultStayPolicyResolver(lookup);
  }
}
