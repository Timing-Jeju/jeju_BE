package com.timingjeju.api.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
      "timing-jeju.test.context=runtime-contract-openapi"
    })
@AutoConfigureMockMvc
class FrontendOpenApiRuntimeIntegrationTest {
  private static final String JWT_KEY = randomKey();

  @org.springframework.test.context.DynamicPropertySource
  static void jwtKey(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  private static String randomKey() {
    byte[] bytes = new byte[48];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getEncoder().encodeToString(bytes);
  }

  @Autowired private MockMvc mvc;

  @Test
  void 생성적용은_인증과_ETag_멱등키를_요구하고_최초기준_null을_양방향_문서화한다() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/trips/53000000-0000-0000-0000-000000000001/schedule-generations/53000000-0000-0000-0000-000000000002/candidates/53000000-0000-0000-0000-000000000003/apply"))
        .andExpect(status().isUnauthorized());
    String path =
        "$.paths['/api/v1/trips/{tripId}/schedule-generations/{runId}/candidates/{candidateId}/apply'].post";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(path + ".operationId").value("applyScheduleGenerationCandidate"))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name == 'If-Match')].required")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name == 'Idempotency-Key')].required")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(jsonPath(path + ".responses['200'].headers.ETag").exists())
        .andExpect(jsonPath(path + ".responses['200'].headers.Location").exists())
        .andExpect(
            jsonPath(
                "$.components.schemas.ApplyCandidateRequest.properties.expectedActiveScheduleVersionId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.ApplyCandidateResponse.properties.previousScheduleVersionId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath("$.components.schemas.ApplyCandidateRequest.required")
                .value(org.hamcrest.Matchers.hasItem("expectedActiveScheduleVersionId")))
        .andExpect(
            jsonPath("$.components.schemas.ApplyCandidateResponse.required")
                .value(org.hamcrest.Matchers.hasItem("previousScheduleVersionId")));
  }

  @Test
  void 생성조회는_인증필수이고_실제_DTO의_상태와_nullable_최초기준을_문서화한다() throws Exception {
    mvc.perform(
            get(
                "/api/v1/trips/53000000-0000-0000-0000-000000000001/schedule-generations/53000000-0000-0000-0000-000000000002"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/schedule-generations/{runId}'].get.operationId")
                .value("getScheduleGeneration"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/schedule-generations/{runId}'].get.requestBody")
                .doesNotExist())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/schedule-generations/{runId}'].get.responses['200'].headers['Retry-After']")
                .exists())
        .andExpect(
            jsonPath("$.components.schemas.GenerationRunStatus.properties.status.enum")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "queued", "running", "succeeded", "failed", "cancelled")))
        .andExpect(
            jsonPath("$.components.schemas.GenerationRunStatus.required")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "contractVersion",
                        "runId",
                        "status",
                        "pollUrl",
                        "commandInputHash",
                        "createdAt")))
        .andExpect(
            jsonPath(
                "$.components.schemas.GenerationResult.properties.baseScheduleVersionId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath("$.components.schemas.GenerationResult.required")
                .value(org.hamcrest.Matchers.hasItem("baseScheduleVersionId")))
        .andExpect(
            jsonPath("$.components.schemas.GenerationCandidate.properties.score.type")
                .value("number"));
  }

  @Test
  void 플래너조건_저장은_필수헤더와_실제_저장응답_예시를_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.parameters[?(@.name == 'If-Match')].required")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.parameters[?(@.name == 'Idempotency-Key')].required")
                .value(org.hamcrest.Matchers.contains(true)))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.responses['200'].headers.ETag")
                .exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.responses['200'].headers['Idempotency-Replayed']")
                .exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.tags[0]")
                .value("여행"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.requestBody.content['application/json'].example.dayAnchors[0].lodgingPlaceId")
                .isString())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.responses['200'].content['application/json'].example.plannerConditions.styleCodes[0]")
                .value("relaxed"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/planner-conditions'].put.responses['422'].content['application/problem+json'].example.code")
                .value("TRIP_CONSTRAINT_VIOLATION"));
  }

  @Test
  void 여행입력_복원과_장소체류시간의_공개예시는_현재_저장계약을_보여준다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}'].get.responses['200'].content['application/json'].example.plannerConditions.dayAnchors")
                .isArray())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}'].get.responses['200'].content['application/json'].example.plannerConditions.styleCodes")
                .isArray())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}'].patch.responses['200'].content['application/json'].example.placePreferences")
                .isArray())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/place-preferences'].put.requestBody.content['application/json'].example.items[0].requestedStayMinutes")
                .value(90))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/place-preferences'].put.responses['200'].content['application/json'].example.items[0].requestedStayMinutes")
                .value(90))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/place-preferences'].put.requestBody.content['application/json'].example.items[1]")
                .value(org.hamcrest.Matchers.hasKey("requestedStayMinutes")));
  }

  @Test
  void 입출도_객체는_필수_필드와_enum을_보존하고_삭제_응답은_null을_허용한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.components.schemas.TransportEvent.required")
                .value(
                    org.hamcrest.Matchers.containsInAnyOrder(
                        "eventType",
                        "transportType",
                        "terminalPlaceId",
                        "customTerminalName",
                        "scheduledAt",
                        "transportNumber",
                        "note")))
        .andExpect(
            jsonPath("$.components.schemas.TransportEvent.properties.eventType.enum")
                .value(org.hamcrest.Matchers.containsInAnyOrder("arrival", "departure")))
        .andExpect(
            jsonPath("$.components.schemas.TransportEvent.properties.transportType.enum")
                .value(org.hamcrest.Matchers.containsInAnyOrder("flight", "ferry")))
        .andExpect(
            jsonPath(
                    "$.components.schemas.TransportEventMutationResponse.properties.event.anyOf[1].type")
                .value("null"));
  }

  @Test
  void 여행_참조_객체의_null과_숙소_필수필드는_생성_계약에_보존된다() throws Exception {
    var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    for (String field : java.util.List.of("arrival", "departure")) {
      String path = "$.components.schemas.TripTransportEvents.properties." + field;
      result
          .andExpect(
              jsonPath(path + ".anyOf[0]['$ref']").value("#/components/schemas/TransportEvent"))
          .andExpect(jsonPath(path + ".anyOf[1].type").value("null"))
          .andExpect(jsonPath(path + "['$ref']").doesNotExist());
    }
    for (String name :
        java.util.List.of(
            "TripDetail", "TripDetailLegacyV11", "TripDetailLegacyV1", "TripSummary")) {
      result.andExpect(
          jsonPath("$.components.schemas." + name + ".properties.scoreProvenance.anyOf[1].type")
              .value("null"));
    }
    result.andExpect(
        jsonPath("$.components.schemas.AccommodationPayload.required")
            .value(
                org.hamcrest.Matchers.containsInAnyOrder(
                    "accommodationId",
                    "placeId",
                    "customName",
                    "name",
                    "checkInDate",
                    "checkOutDate",
                    "checkInTime",
                    "checkOutTime",
                    "sequenceNo")));
  }

  @Test
  void 실제_catalog의_OpenAPI는_현재_입력과_nullable_응답을_문서화한다() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.components.schemas.TransportEventRequest.properties.customTerminalName.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.CreateAccommodationRequest.properties.placeId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                "$.components.schemas.AccommodationMutationPayload.properties.activeScheduleVersionId.type",
                org.hamcrest.Matchers.hasItem("null")))
        .andExpect(
            jsonPath(
                    "$.components.schemas.ScheduleMutationResponse.properties.feasibilityStale.const")
                .value(true))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/trips/{tripId}/schedule-order'].put.requestBody.content['application/json'].schema['$ref']")
                .value("#/components/schemas/ReorderScheduleRequest"))
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='lat')]")
                .isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.parameters[?(@.name=='placeId')]")
                .isNotEmpty())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/accommodations']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/trips/{tripId}/schedule']").exists());
  }
}
