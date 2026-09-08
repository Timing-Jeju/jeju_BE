package com.timingjeju.api.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class ScheduleOpenApiIntegrationTest {
  private static final String JWT_KEY = randomKey();
  @Autowired private MockMvc mvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @DynamicPropertySource
  static void jwtKey(DynamicPropertyRegistry registry) {
    registry.add("app.security.jwt.secret", () -> JWT_KEY);
  }

  @Test
  void schedule_read는_not_ready에서_runtime_query_DTO_problem을_문서화한다() throws Exception {
    String path = "$.paths['/api/v1/trips/{tripId}/schedule'].get";
    String success = path + ".responses['200'].content['application/json']";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(path + ".operationId").value("tripScheduleRead"))
        .andExpect(jsonPath(path + ".tags[0]").value("일정"))
        .andExpect(jsonPath(path + ".parameters", hasSize(2)))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='tripId')].required").value(true))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='tripId')].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='versionId')].required").value(false))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='versionId')].schema.pattern")
                .value("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
        .andExpect(jsonPath(path + ".requestBody").doesNotExist())
        .andExpect(
            jsonPath(path + ".responses.keys()")
                .value(containsInAnyOrder("200", "400", "401", "403", "404", "500")))
        .andExpect(
            jsonPath(success + ".schema.$ref").value("#/components/schemas/ScheduleResponse"))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleResponse.required")
                .value(containsInAnyOrder("tripId", "scheduleVersion", "days")))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleResponse.properties.scheduleVersion.$ref")
                .value("#/components/schemas/ScheduleVersion"))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleVersion.required")
                .value(
                    containsInAnyOrder(
                        "scheduleVersionId",
                        "versionNo",
                        "status",
                        "sourceType",
                        "baseScheduleVersionId",
                        "score",
                        "feasibilityStale")))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleVersion.properties.score.type")
                .value(containsInAnyOrder("integer", "null")))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleItem.properties.progress.type")
                .value(containsInAnyOrder("object", "null")))
        .andExpect(
            jsonPath("$.components.schemas.ScheduleLeg.properties.transportMode.enum")
                .value(containsInAnyOrder("walk", "public_transit", "rental_car", "taxi")))
        .andExpect(jsonPath(success + ".example.tripId").exists())
        .andExpect(
            jsonPath(success + ".example.days[0].items[0].plannedStartAt")
                .value("2026-09-01T09:00:00+09:00"))
        .andExpect(
            jsonPath(path + ".responses['404'].content['application/problem+json'].example.code")
                .value("SCHEDULE_VERSION_NOT_FOUND"))
        .andExpect(
            jsonPath(path + ".responses['500'].$ref")
                .value("#/components/responses/InternalServerProblem"))
        .andExpect(
            jsonPath(
                    "$.components.responses.InternalServerProblem.content['application/problem+json'].example.code")
                .value("INTERNAL_SERVER_ERROR"));
  }

  @Test
  void schedule_item_create는_필수_header_body와_응답을_OpenAPI에_공개한다() throws Exception {
    String path = "$.paths['/api/v1/trips/{tripId}/schedule-items'].post";
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(path + ".operationId").value("tripScheduleItemCreate"))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='tripId')].required").value(true))
        .andExpect(jsonPath(path + ".parameters[?(@.name=='If-Match')].required").value(true))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='Idempotency-Key')].required").value(true))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='Idempotency-Key')].schema.minLength").value(1))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='Idempotency-Key')].schema.maxLength")
                .value(128))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='Idempotency-Key')].schema.pattern")
                .value("^[ -~]{1,128}$"))
        .andExpect(
            jsonPath(path + ".parameters[?(@.name=='Idempotency-Key')].schema.format")
                .doesNotExist())
        .andExpect(jsonPath(path + ".requestBody.required").value(true))
        .andExpect(jsonPath(path + ".responses['201'].headers.ETag").exists())
        .andExpect(jsonPath(path + ".responses['201'].headers.Idempotency-Replayed").exists())
        .andExpect(
            jsonPath(path + ".responses.keys()")
                .value(containsInAnyOrder("201", "400", "401", "403", "404", "409", "422", "500")))
        .andExpect(
            jsonPath(path + ".responses['400'].content['application/problem+json'].examples.keys()")
                .value(
                    containsInAnyOrder(
                        "INVALID_REQUEST", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID")))
        .andExpect(
            jsonPath(path + ".responses['401'].content['application/problem+json'].examples.keys()")
                .value(containsInAnyOrder("AUTHENTICATION_REQUIRED", "INVALID_ACCESS_TOKEN")))
        .andExpect(
            jsonPath(path + ".responses['404'].content['application/problem+json'].examples.keys()")
                .value(
                    containsInAnyOrder(
                        "TRIP_NOT_FOUND",
                        "PLACE_NOT_FOUND",
                        "ACCOMMODATION_NOT_FOUND",
                        "TRANSPORT_EVENT_NOT_FOUND",
                        "SCHEDULE_VERSION_NOT_FOUND")))
        .andExpect(
            jsonPath(
                    path
                        + ".responses['404'].content['application/problem+json'].examples.ACCOMMODATION_NOT_FOUND.value.detail")
                .value("요청한 숙소가 없거나 해당 여행에 속하지 않습니다."))
        .andExpect(
            jsonPath(
                    path
                        + ".responses['404'].content['application/problem+json'].examples.TRANSPORT_EVENT_NOT_FOUND.value.detail")
                .value("요청한 교통 이벤트가 없거나 해당 여행에 속하지 않습니다."))
        .andExpect(
            jsonPath(path + ".responses['409'].content['application/problem+json'].examples.keys()")
                .value(
                    containsInAnyOrder(
                        "IDEMPOTENCY_KEY_REUSED",
                        "TRIP_VERSION_CONFLICT",
                        "TRIP_TERMINAL_STATE_CONFLICT",
                        "ACTIVE_SCHEDULE_VERSION_CONFLICT")))
        .andExpect(jsonPath(path + ".responses['409'].headers['Retry-After']").exists())
        .andExpect(
            jsonPath(path + ".responses['422'].content['application/problem+json'].examples.keys()")
                .value(containsInAnyOrder("SCHEDULE_ITEM_INVALID", "SCHEDULE_LEG_INCOMPLETE")));
  }

  @Test
  void schedule_edit_OpenAPI_example은_실행가능한_단일_reference와_DELETE_empty_changedIds를_쓴다()
      throws Exception {
    JsonNode document =
        objectMapper.readTree(
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    JsonNode patch =
        document.at(
            "/paths/~1api~1v1~1trips~1{tripId}~1schedule-items~1{itemId}/patch/requestBody/content/application~1json/example");
    JsonNode deleted =
        document.at(
            "/paths/~1api~1v1~1trips~1{tripId}~1schedule-items~1{itemId}/delete/responses/200/content/application~1json/example");

    assertThat(patch.path("placeId").isTextual()).isTrue();
    assertThat(patch.has("accommodationId")).isFalse();
    assertThat(patch.has("transportEventId")).isFalse();
    assertThat(deleted.path("changedItemIds").isArray()).isTrue();
    assertThat(deleted.path("changedItemIds")).isEmpty();
  }

  @Test
  void schedule_item_create_Idempotency_Key_pattern은_canonical_contract와_문자열까지_같다()
      throws Exception {
    String document =
        mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
    JsonNode parameters =
        objectMapper
            .readTree(document)
            .at("/paths/~1api~1v1~1trips~1{tripId}~1schedule-items/post/parameters");
    JsonNode idempotencyKey =
        java.util.stream.StreamSupport.stream(parameters.spliterator(), false)
            .filter(parameter -> "Idempotency-Key".equals(parameter.path("name").asText()))
            .findFirst()
            .orElseThrow();

    assertThat(idempotencyKey.at("/schema/pattern").asText()).isEqualTo("^[ -~]{1,128}$");
  }

  private static String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
