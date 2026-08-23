package com.timingjeju.api.global.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class DemoSecurityConfiguration {
  @Bean
  @Order(0)
  SecurityFilterChain demoSecurityFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/v1/demo/**");
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.csrf(csrf -> csrf.disable());
    http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
    return http.build();
  }
}
