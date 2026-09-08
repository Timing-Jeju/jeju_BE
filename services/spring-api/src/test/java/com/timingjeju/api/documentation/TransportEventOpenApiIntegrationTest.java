package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
      "app.places.cursor-signing-key=test-only-place-cursor-key-with-at-least-32-bytes"
    })
@AutoConfigureMockMvc
class TransportEventOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void 교통이벤트_PUT_DELETE는_not_ready에서_runtime_DTO와_problem을_문서화한다() throws Exception {
    String path = "$.paths['/api/v1/trips/{tripId}/transport-event']";
    String putSchema = path + ".put.requestBody.content['application/json'].schema";
    String responseSchema = path + ".put.responses['200'].content['application/json'].schema";

    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(path + ".put.operationId").value("tripTransportEventsUpdate"))
        .andExpect(jsonPath(path + ".delete.operationId").value("tripTransportEventsDelete"))
        .andExpect(jsonPath(path + ".put.parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(path + ".delete.parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(jsonPath(path + ".delete.parameters[?(@.name=='eventType')]").value(hasSize(1)))
        .andExpect(jsonPath(path + ".delete.requestBody").doesNotExist())
        .andExpect(
            jsonPath(putSchema + ".$ref").value("#/components/schemas/TransportEventRequest"))
        .andExpect(
            jsonPath("$.components.schemas.TransportEventRequest.properties.keys()")
                .value(
                    containsInAnyOrder(
                        "eventType",
                        "transportType",
                        "terminalPlaceId",
                        "customTerminalName",
                        "scheduledAt",
                        "transportNumber",
                        "note")))
        .andExpect(
            jsonPath(
                    "$.components.schemas.TransportEventRequest.properties.terminalPlaceId.writeOnly")
                .value(true))
        .andExpect(
            jsonPath("$.components.schemas.TransportEventRequest.properties.scheduledAt.writeOnly")
                .value(true))
        .andExpect(
            jsonPath(responseSchema + ".$ref")
                .value("#/components/schemas/TransportEventMutationResponse"))
        .andExpect(
            jsonPath("$.components.schemas.TransportEventMutationResponse.properties.*")
                .value(hasSize(9)))
        .andExpect(jsonPath(path + ".put.responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(path + ".delete.responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(path + ".put.responses['503']").doesNotExist())
        .andExpect(jsonPath(path + ".delete.responses['503']").doesNotExist())
        .andExpect(
            jsonPath(
                    path + ".put.responses['404'].content['application/problem+json'].example.code")
                .value("TRIP_NOT_FOUND"))
        .andExpect(
            jsonPath(
                    path + ".put.responses['409'].content['application/problem+json'].example.code")
                .value("TRIP_VERSION_CONFLICT"))
        .andExpect(
            jsonPath(
                    path
                        + ".delete.responses['404'].content['application/problem+json'].example.code")
                .value("TRANSPORT_EVENT_NOT_FOUND"))
        .andExpect(
            jsonPath(
                    path + ".put.responses['422'].content['application/problem+json'].example.code")
                .value("TRANSPORT_EVENT_CONSTRAINT_VIOLATION"))
        .andExpect(
            jsonPath(
                    path
                        + ".delete.responses['404'].content['application/problem+json'].example.code")
                .value("TRANSPORT_EVENT_NOT_FOUND"));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
