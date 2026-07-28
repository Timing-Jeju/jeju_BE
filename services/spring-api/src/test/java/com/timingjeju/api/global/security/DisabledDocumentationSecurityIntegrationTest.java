package com.timingjeju.api.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.secret=test-" + "only-hs256-secret-with-at-least-32-bytes",
      "springdoc.api-docs.enabled=false",
      "springdoc.swagger-ui.enabled=false"
    })
@AutoConfigureMockMvc
class DisabledDocumentationSecurityIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void 비활성화된_OpenAPI와_Swagger_경로는_명시적으로_거부한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    mockMvc
        .perform(get("/swagger-ui/index.html"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
  }
}
