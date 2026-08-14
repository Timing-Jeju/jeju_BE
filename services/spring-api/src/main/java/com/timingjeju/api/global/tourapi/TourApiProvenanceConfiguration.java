package com.timingjeju.api.global.tourapi;

import com.timingjeju.api.application.tourapi.TourApiRequestFingerprinter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TourApiProvenanceConfiguration {

  @Bean
  TourApiRequestFingerprinter tourApiRequestFingerprinter() {
    return new Sha256CanonicalTourApiRequestFingerprinter();
  }
}
