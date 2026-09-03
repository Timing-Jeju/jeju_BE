package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.global.security.StrictBearerTokenResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.data-health.operator", name = "enabled", havingValue = "true")
public class ExternalDataHealthSecurityConfiguration {

  @Bean
  OpsJwtDecoderHolder opsJwtDecoderHolder(ExternalDataHealthOperatorProperties properties) {
    return new OpsJwtDecoderHolder(OpsJwtDecoderFactory.create(properties));
  }

  @Bean
  @Order(0)
  SecurityFilterChain externalDataHealthSecurityFilterChain(
      HttpSecurity http, OpsJwtDecoderHolder decoderHolder) throws Exception {
    http.securityMatcher(EndpointRequest.to(ExternalDataHealthEndpoint.class));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.csrf(csrf -> csrf.disable());
    http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
    http.oauth2ResourceServer(
        resourceServer ->
            resourceServer
                .jwt(jwt -> jwt.decoder(decoderHolder))
                .bearerTokenResolver(new StrictBearerTokenResolver()));
    return http.build();
  }
}
