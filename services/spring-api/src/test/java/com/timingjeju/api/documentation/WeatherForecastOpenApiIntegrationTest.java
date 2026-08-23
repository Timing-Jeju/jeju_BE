package com.timingjeju.api.documentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.Base64;
import org.hamcrest.Matchers;
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
      "timing-jeju.test.context=weather-openapi"
    })
@AutoConfigureMockMvc
class WeatherForecastOpenApiIntegrationTest {

  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void weather_forecast는_optional_JWT와_canonical_query_response_error를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/weather/forecast'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/weather/forecast'].get.security.length()").value(2))
        .andExpect(jsonPath("$.paths['/api/v1/weather/forecast'].get.security[0]").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.security[1].bearerAuth").isArray())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')].required")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')].schema.exclusiveMinimum")
                .value(-90))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')].schema.exclusiveMaximum")
                .value(90))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lng')].schema.minimum")
                .value(-180.0))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lng')].schema.maximum")
                .value(180.0))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='dateTime')].required")
                .value(true))
        .andExpect(
            jsonPath(
                "$.paths['/api/v1/weather/forecast'].get.responses",
                Matchers.allOf(
                    Matchers.hasKey("200"),
                    Matchers.hasKey("400"),
                    Matchers.hasKey("401"),
                    Matchers.hasKey("422"),
                    Matchers.hasKey("503"))))
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.responses['403']").doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.WeatherForecastResponse.additionalProperties")
                .value(false))
        .andExpect(
            jsonPath("$.components.schemas.WeatherForecastResponse.required")
                .value(
                    Matchers.hasItems(
                        "observedAt", "expiresAt", "stale", "fallbackUsed", "forecastedAt")))
        .andExpect(jsonPath("$.components.schemas.WeatherGrid.additionalProperties").value(false));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
