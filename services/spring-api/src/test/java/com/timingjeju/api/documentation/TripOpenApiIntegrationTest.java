package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
class TripOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void trip_create_list_detail은_bearer_DTO_cursor와_problem을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}'].get").exists())
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/v1/trips'].get.parameters[?(@.name=='size')].schema.maximum")
                .value(50))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.requestBody.content['application/json'].schema.$ref")
                .value("#/components/schemas/CreateTripRequest"))
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['201']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['400']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['401']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/trips'].post.responses['201'].headers.Location").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['201'].headers.ETag").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.responses['201'].headers['Idempotency-Replayed']")
                .exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['409']").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.responses['409'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['422']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips'].post.responses['503']").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips'].post.responses['503'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}'].get.responses['404']").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}'].get.parameters[?(@.name=='tripId')].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.required")
                .value(containsInAnyOrder("title", "startDate", "endDate")))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.title.minLength").value(1))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.title.maxLength")
                .value(100))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.timezone.default")
                .value("Asia/Seoul"))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.timezone.enum[0]")
                .value("Asia/Seoul"))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.userPace.default")
                .value("normal"))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.userPace.enum")
                .value(containsInAnyOrder("slow", "normal", "fast")))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.transportModes.nullable")
                .doesNotExist())
        .andExpect(
            jsonPath(
                    "$.components.schemas.CreateTripRequest.properties.transportModes.default[0].mode")
                .value("public_transit"))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CreateTripRequest.properties.transportModes.default[0].priority")
                .value(1))
        .andExpect(
            jsonPath(
                    "$.components.schemas.CreateTripRequest.properties.transportModes.default[0].primary")
                .value(true))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.transportModes.minItems")
                .value(1))
        .andExpect(
            jsonPath("$.components.schemas.CreateTripRequest.properties.transportModes.maxItems")
                .value(3))
        .andExpect(jsonPath("$.components.schemas.TransportMode.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.TransportMode.required")
                .value(containsInAnyOrder("mode", "priority", "primary")))
        .andExpect(
            jsonPath("$.components.schemas.TransportMode.properties.mode.enum")
                .value(containsInAnyOrder("public_transit", "rental_car", "taxi")))
        .andExpect(
            jsonPath("$.components.schemas.TransportMode.properties.priority.minimum").value(1))
        .andExpect(
            jsonPath("$.components.schemas.TransportMode.properties.priority.maximum").value(3))
        .andExpect(jsonPath("$.components.schemas.TripDetail.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.required")
                .value(
                    containsInAnyOrder(
                        "tripId",
                        "title",
                        "status",
                        "startDate",
                        "endDate",
                        "timezone",
                        "userPace",
                        "transportModes",
                        "days",
                        "activeScheduleVersionId",
                        "totalScore",
                        "scoreProvenance",
                        "scheduleEffect",
                        "regenerationRequired",
                        "createdAt",
                        "updatedAt")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.status.enum")
                .value(
                    containsInAnyOrder(
                        "draft",
                        "generating",
                        "planned",
                        "live",
                        "completed",
                        "cancelled",
                        "failed")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.scheduleEffect.enum")
                .value(containsInAnyOrder("none", "maintained", "invalidated")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.activeScheduleVersionId.type")
                .value(containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.totalScore.type")
                .value(containsInAnyOrder("integer", "null")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.scoreProvenance.type")
                .value(containsInAnyOrder("object", "null")))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.totalScore.minimum").value(0))
        .andExpect(
            jsonPath("$.components.schemas.TripDetail.properties.totalScore.maximum").value(100))
        .andExpect(jsonPath("$.components.schemas.TripDay.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.TripDay.required")
                .value(containsInAnyOrder("dayId", "dayNo", "date")))
        .andExpect(jsonPath("$.components.schemas.TripDay.properties.dayNo.minimum").value(1))
        .andExpect(jsonPath("$.components.schemas.TripDay.properties.dayNo.maximum").value(30))
        .andExpect(
            jsonPath("$.components.schemas.ScoreProvenance.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.ScoreProvenance.required")
                .value(
                    containsInAnyOrder(
                        "source",
                        "runId",
                        "scheduleVersionId",
                        "calculatedAt",
                        "observedAt",
                        "expiresAt",
                        "stale")))
        .andExpect(
            jsonPath("$.components.schemas.ScoreProvenance.properties.source.enum[0]")
                .value("feasibility_run"))
        .andExpect(jsonPath("$.components.schemas.TripSummary.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.TripSummary.required")
                .value(
                    containsInAnyOrder(
                        "tripId",
                        "title",
                        "status",
                        "startDate",
                        "endDate",
                        "timezone",
                        "activeScheduleVersionId",
                        "totalScore",
                        "scoreProvenance",
                        "createdAt",
                        "updatedAt")))
        .andExpect(
            jsonPath("$.components.schemas.TripSummary.properties.activeScheduleVersionId.type")
                .value(containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath("$.components.schemas.TripSummary.properties.totalScore.type")
                .value(containsInAnyOrder("integer", "null")))
        .andExpect(
            jsonPath("$.components.schemas.TripsListResponse.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.TripsListResponse.required")
                .value(containsInAnyOrder("items", "page")))
        .andExpect(jsonPath("$.components.schemas.CursorPage.additionalProperties").value(false))
        .andExpect(
            jsonPath("$.components.schemas.CursorPage.required")
                .value(containsInAnyOrder("size", "hasNext", "nextCursor")))
        .andExpect(jsonPath("$.components.schemas.CursorPage.properties.size.minimum").value(1))
        .andExpect(jsonPath("$.components.schemas.CursorPage.properties.size.maximum").value(50))
        .andExpect(
            jsonPath("$.components.schemas.CursorPage.properties.nextCursor.type")
                .value(containsInAnyOrder("string", "null")))
        .andExpect(
            jsonPath("$.components.schemas.CursorPage.properties.nextCursor.maxLength")
                .value(2048));
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
