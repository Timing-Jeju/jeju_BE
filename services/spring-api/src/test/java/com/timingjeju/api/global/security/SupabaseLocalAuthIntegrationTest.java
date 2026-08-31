package com.timingjeju.api.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProvisionedCurrentUser;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SUPABASE_ACCESS_TOKEN", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SupabaseLocalAuthIntegrationTest.LocalAuthEndpointConfig.class)
class SupabaseLocalAuthIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void 로컬_Supabase_Auth_access_token으로_Spring_보호_API에_접근한다() throws Exception {
    String accessToken = System.getenv("SUPABASE_ACCESS_TOKEN");

    mockMvc
        .perform(
            get("/api/v1/test/local-auth-user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").isNotEmpty())
        .andExpect(jsonPath("$.role").value("AUTHENTICATED"));

    mockMvc
        .perform(
            get("/api/v1/test/local-auth-user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperSignature(accessToken)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());

    mockMvc
        .perform(
            get("/api/v1/test/local-auth-profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").isNotEmpty())
        .andExpect(jsonPath("$.providers").isEmpty());

    mockMvc
        .perform(
            get("/api/v1/test/local-auth-profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").isNotEmpty())
        .andExpect(jsonPath("$.providers").isEmpty());

    mockMvc
        .perform(get("/not-allowed").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_DENIED"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  private String tamperSignature(String token) {
    int signatureStart = token.lastIndexOf('.') + 1;
    char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
    return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
  }

  static class LocalAuthEndpointConfig {

    @Bean
    LocalAuthTestController localAuthTestController(
        CurrentUserAccessor currentUserAccessor,
        CurrentUserProvisioningService provisioningService) {
      return new LocalAuthTestController(currentUserAccessor, provisioningService);
    }
  }

  @RestController
  static class LocalAuthTestController {

    private final CurrentUserAccessor currentUserAccessor;
    private final CurrentUserProvisioningService service;

    LocalAuthTestController(
        CurrentUserAccessor currentUserAccessor, CurrentUserProvisioningService service) {
      this.currentUserAccessor = currentUserAccessor;
      this.service = service;
    }

    @GetMapping("/api/v1/test/local-auth-user")
    CurrentUser currentUser() {
      return currentUserAccessor.getRequired();
    }

    @GetMapping("/api/v1/test/local-auth-profile")
    ProvisionedCurrentUser provisionedProfile() {
      return service.provision(currentUserAccessor.getRequired());
    }
  }
}
