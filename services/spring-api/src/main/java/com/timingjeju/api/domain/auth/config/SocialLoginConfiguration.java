package com.timingjeju.api.domain.auth.config;

import com.timingjeju.api.domain.auth.service.NaverUserInfoAdmissionService;
import com.timingjeju.api.domain.auth.service.NaverUserInfoGateway;
import com.timingjeju.api.domain.auth.service.NaverUserInfoHttpGateway;
import com.timingjeju.api.domain.auth.service.NaverUserInfoService;
import com.timingjeju.api.domain.auth.service.SocialLoginCatalogService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocialLoginProperties.class)
public class SocialLoginConfiguration {

  @Bean
  SocialLoginCatalogService socialLoginCatalogService(SocialLoginProperties properties) {
    return new SocialLoginCatalogService(properties);
  }

  @Bean
  NaverUserInfoGateway naverUserInfoGateway(ObjectMapper objectMapper) {
    return NaverUserInfoHttpGateway.production(objectMapper);
  }

  @Bean
  NaverUserInfoAdmissionService naverUserInfoAdmissionService() {
    return NaverUserInfoAdmissionService.production();
  }

  @Bean
  NaverUserInfoService naverUserInfoService(
      NaverUserInfoGateway gateway, NaverUserInfoAdmissionService admissionService) {
    return new NaverUserInfoService(gateway, admissionService);
  }
}
