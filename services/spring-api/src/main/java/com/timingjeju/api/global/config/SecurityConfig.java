package com.timingjeju.api.global.config;

import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.global.security.AppCorsProperties;
import com.timingjeju.api.global.security.CurrentUserJwtAuthenticationConverter;
import com.timingjeju.api.global.security.JsonAccessDeniedHandler;
import com.timingjeju.api.global.security.JsonAuthenticationEntryPoint;
import com.timingjeju.api.global.security.JwksJwtDecoderStrategy;
import com.timingjeju.api.global.security.JwtDecoderStrategy;
import com.timingjeju.api.global.security.LocalHs256JwtDecoderStrategy;
import com.timingjeju.api.global.security.SecurityAuthenticationFailureHandler;
import com.timingjeju.api.global.security.SecurityContextCurrentUserAccessor;
import com.timingjeju.api.global.security.SecurityErrorResponseWriter;
import com.timingjeju.api.global.security.SecurityRuntimeEnvironmentResolver;
import com.timingjeju.api.global.security.SecurityRuntimePolicy;
import com.timingjeju.api.global.security.StrictBearerTokenResolver;
import com.timingjeju.api.global.security.SupabaseJwtDecoderFactory;
import com.timingjeju.api.global.security.SupabaseJwtProperties;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SupabaseJwtProperties.class, AppCorsProperties.class})
public class SecurityConfig {

  private static final Set<String> PUBLIC_SOCIAL_GET_PATHS =
      Set.of("/api/v1/auth/social/providers", "/api/v1/auth/social/naver/userinfo");

  @Bean
  JwtDecoderStrategy jwksJwtDecoderStrategy() {
    return new JwksJwtDecoderStrategy();
  }

  @Bean
  JwtDecoderStrategy localHs256JwtDecoderStrategy() {
    return new LocalHs256JwtDecoderStrategy();
  }

  @Bean
  JwtDecoder jwtDecoder(
      SupabaseJwtProperties properties,
      Environment environment,
      List<JwtDecoderStrategy> strategies) {
    SecurityRuntimePolicy runtimePolicy = SecurityRuntimeEnvironmentResolver.resolve(environment);
    return new SupabaseJwtDecoderFactory(properties, runtimePolicy, strategies).create();
  }

  @Bean
  CurrentUserAccessor currentUserAccessor() {
    return new SecurityContextCurrentUserAccessor();
  }

  @Bean
  SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
    return new SecurityErrorResponseWriter(objectMapper);
  }

  @Bean
  @Order(1)
  SecurityFilterChain socialLoginSecurityFilterChain(
      HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
    http.securityMatcher(
        request ->
            "GET".equals(request.getMethod())
                && PUBLIC_SOCIAL_GET_PATHS.contains(
                    request.getRequestURI().substring(request.getContextPath().length())));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.csrf(csrf -> csrf.disable());
    http.cors(cors -> cors.configurationSource(corsConfigurationSource));
    http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      CorsConfigurationSource corsConfigurationSource,
      SecurityErrorResponseWriter responseWriter,
      @Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled,
      @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUiEnabled)
      throws Exception {
    JsonAuthenticationEntryPoint authenticationEntryPoint =
        new JsonAuthenticationEntryPoint(responseWriter);
    JsonAccessDeniedHandler accessDeniedHandler = new JsonAccessDeniedHandler(responseWriter);
    SecurityAuthenticationFailureHandler authenticationFailureHandler =
        new SecurityAuthenticationFailureHandler(authenticationEntryPoint, responseWriter);

    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    // Authorization 헤더의 Bearer token만 사용하고 쿠키 세션을 만들지 않으므로 CSRF를 비활성화한다.
    http.csrf(csrf -> csrf.disable());
    http.cors(cors -> cors.configurationSource(corsConfigurationSource));
    http.exceptionHandling(
        exceptions ->
            exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));
    http.authorizeHttpRequests(
        requests -> {
          requests.requestMatchers("/actuator/health", "/actuator/info").permitAll();
          if (apiDocsEnabled) {
            requests.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll();
          }
          if (swaggerUiEnabled) {
            requests.requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll();
          }
          requests.requestMatchers("/api/v1/**").authenticated();
          requests.anyRequest().denyAll();
        });
    http.oauth2ResourceServer(
        resourceServer ->
            resourceServer
                .withObjectPostProcessor(
                    new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                      @Override
                      public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(authenticationFailureHandler);
                        return filter;
                      }
                    })
                .jwt(
                    jwt ->
                        jwt.decoder(jwtDecoder)
                            .jwtAuthenticationConverter(
                                new CurrentUserJwtAuthenticationConverter()))
                .bearerTokenResolver(new StrictBearerTokenResolver())
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }
}
