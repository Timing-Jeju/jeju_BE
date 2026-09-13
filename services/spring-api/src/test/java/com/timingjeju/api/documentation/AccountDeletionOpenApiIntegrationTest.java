package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("slice")
@SpringBootTest(
    properties = {
      "spring.profiles.active=local-hs256",
      "app.security.jwt.issuer=http://127.0.0.1:54321/auth/v1",
      "app.security.jwt.audience=authenticated",
      "app.security.jwt.jwks-url=",
      "app.security.cors.allowed-origins=http://localhost:3000",
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes",
      "app.account-deletion.enabled=true",
      "app.account-deletion.worker.id=openapi-test-worker",
      "app.account-deletion.worker.initial-delay=PT24H",
      "app.account-deletion.worker.supabase-url=https://project.supabase.invalid",
      "app.account-deletion.worker.service-role-key=placeholder-service-role"
    })
@AutoConfigureMockMvc
class AccountDeletionOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void OpenAPI는_탈퇴_접수와_인증없는_status_polling_계약을_공개한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/me'].delete.responses['202']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/me'].delete.parameters[?(@.name=='Idempotency-Key')]")
                .value(hasSize(1)))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].delete.parameters[?(@.name=='Idempotency-Key')].schema.minLength")
                .value(1))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].delete.parameters[?(@.name=='Idempotency-Key')].schema.maxLength")
                .value(128))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/account-deletion-requests/{deletionRequestId}'].get.parameters[?(@.name=='X-Deletion-Status-Token')]")
                .value(hasSize(1)))
        .andExpect(
            jsonPath("$.components.schemas.AccountDeletionStatusResponse.properties.userId")
                .doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.AccountDeletionStatusResponse.properties.statusToken")
                .doesNotExist());
  }

  @Test
  void status_polling은_JWT없이_진입하되_missing_token은_전용_401로_닫는다() throws Exception {
    mvc.perform(get("/api/v1/account-deletion-requests/01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_DELETION_STATUS_TOKEN"));
  }

  private static String randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }
}
