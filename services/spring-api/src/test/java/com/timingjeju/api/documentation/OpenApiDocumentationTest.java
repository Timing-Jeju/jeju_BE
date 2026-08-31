package com.timingjeju.api.documentation;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void non_local_profile에는_demo_경로가_등록되지_않는다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/demo/imports/tour-api']").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/demo/storage']").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/demo/storage/view']").doesNotExist());

    org.assertj.core.api.Assertions.assertThat(handlerMapping.getHandlerMethods().keySet())
        .noneMatch(
            mapping ->
                mapping.getPatternValues().stream()
                    .anyMatch(pattern -> pattern.startsWith("/api/v1/demo/")));
  }

  @Test
  void OpenAPI_JSON에_서비스_기본_정보가_포함된다() throws Exception {
    String openApiJson =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
            .andExpect(jsonPath("$.info.title").value("Timing Jeju API"))
            .andExpect(jsonPath("$.info.version").value("v1"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    Path output = Path.of("build", "openapi", "openapi.json");
    Files.createDirectories(output.getParent());
    Files.writeString(output, openApiJson, StandardCharsets.UTF_8);
  }

  @Test
  void OpenAPI는_stable_operationId_JSON_media와_검증가능한_example을_제공한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/me'].get.operationId").value("profileRead"))
        .andExpect(jsonPath("$.paths['/api/v1/me'].patch.operationId").value("profileUpdate"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].patch.requestBody.content['application/json'].example.nickname")
                .value("제주 산책자"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].get.responses['200'].content['application/json'].example.userId")
                .value("18000000-0000-4000-8000-000000000018"))
        .andExpect(
            jsonPath("$.paths['/api/v1/me'].get.responses['200'].content['*/*']").doesNotExist())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].get.responses['401'].content['application/problem+json'].example.code")
                .value("AUTHENTICATION_REQUIRED"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].patch.responses['409'].content['application/problem+json'].example.code")
                .value("PROFILE_CONFLICT"))
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.parameters[0].description").isNotEmpty())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.parameters[0].example").exists())
        .andExpect(jsonPath("$.components.headers.TraceId.example").exists())
        .andExpect(jsonPath("$.components.parameters['If-Match'].required").value(true))
        .andExpect(jsonPath("$.components.parameters['Idempotency-Key'].required").value(true));
  }

  @Test
  void OpenAPI는_optional_security를_problem_pipeline보다_먼저_적용한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/legal-documents'].get.security[0]").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/legal-documents'].get.security[1].bearerAuth").isArray())
        .andExpect(
            jsonPath("$.paths['/api/v1/legal-documents'].get.responses['403']").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/places'].get.responses['403']").doesNotExist())
        .andExpect(
            jsonPath("$.paths['/api/v1/places/{placeId}'].get.responses['403']").doesNotExist())
        .andExpect(
            jsonPath("$.paths['/api/v1/weather/forecast'].get.responses['403']").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/me'].get.responses['403']").exists());
  }

  @Test
  void OpenAPI_problem_example은_runtime_registry의_type_code_status를_그대로_사용한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me'].get.responses['503'].content['application/problem+json'].example.type")
                .value("https://api.timing-jeju.example/problems/profile-data-unavailable"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/me/consents'].put.responses['422'].content['application/problem+json'].example.type")
                .value("https://api.timing-jeju.example/problems/legal-consent-required"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['401'].content['application/problem+json'].example.type")
                .value("https://api.timing-jeju.example/problems/social-naver-token-invalid"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/places'].get.responses['422'].content['application/problem+json'].example.type")
                .value("https://api.timing-jeju.com/problems/place-query-constraint-violation"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/weather/forecast'].get.responses['503'].content['application/problem+json'].example.type")
                .value("https://api.timing-jeju.com/problems/weather-forecast-unavailable"));
  }

  @Test
  void OpenAPI에는_소셜_로그인_공개_API_경로와_설명이_포함된다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/providers'].get").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/providers'].get.summary").value("소셜 로그인 공급자 조회"))
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.summary")
                .value("Naver Custom OAuth UserInfo 변환"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['200'].content['application/json'].schema.$ref")
                .value("#/components/schemas/NaverUserInfoResponse"))
        .andExpect(
            jsonPath("$.components.schemas.NaverUserInfoResponse.properties.email_verified")
                .doesNotExist())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['429']").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['503']").exists())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['401'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['503'].content['application/problem+json'].schema.$ref")
                .value("#/components/schemas/ApiProblemDetails"))
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/providers'].get.responses['500'].$ref")
                .value("#/components/responses/InternalServerProblem"))
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['500'].$ref")
                .value("#/components/responses/InternalServerProblem"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/providers'].get.responses['200'].headers['X-Trace-Id'].$ref")
                .value("#/components/headers/TraceId"))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/auth/social/naver/userinfo'].get.responses['401'].headers['X-Trace-Id'].$ref")
                .value("#/components/headers/TraceId"))
        .andExpect(jsonPath("$.paths['/api/v1/auth/social/providers'].get.security").isEmpty())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/social/naver/userinfo'].get.security").isEmpty());
  }

  @Test
  void OpenAPI는_공통_Problem_Details_core와_확장_필드를_message_없이_문서화한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.components.schemas.ApiProblemDetails.required")
                .value(
                    containsInAnyOrder(
                        "type",
                        "title",
                        "status",
                        "detail",
                        "instance",
                        "code",
                        "traceId",
                        "fieldErrors")))
        .andExpect(
            jsonPath("$.components.schemas.ApiProblemDetails.properties").value(aMapWithSize(8)))
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.type").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.title").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.status").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.detail").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.instance").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.code").exists())
        .andExpect(jsonPath("$.components.schemas.ApiProblemDetails.properties.traceId").exists())
        .andExpect(
            jsonPath("$.components.schemas.ApiProblemDetails.properties.fieldErrors.type")
                .value("array"))
        .andExpect(
            jsonPath("$.components.schemas.ApiProblemDetails.properties.fieldErrors.items.$ref")
                .value("#/components/schemas/FieldErrorDetail"))
        .andExpect(
            jsonPath("$.components.schemas.ApiProblemDetails.properties.message").doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.FieldErrorDetail.required")
                .value(containsInAnyOrder("field", "detail")))
        .andExpect(
            jsonPath("$.components.schemas.FieldErrorDetail.properties").value(aMapWithSize(2)))
        .andExpect(jsonPath("$.components.schemas.FieldErrorDetail.properties.field").exists())
        .andExpect(jsonPath("$.components.schemas.FieldErrorDetail.properties.detail").exists())
        .andExpect(
            jsonPath("$.components.schemas.FieldErrorDetail.properties.message").doesNotExist());
  }

  @Test
  void saved_place가_병합되면_표준_401과_non_contributor_409_example을_정확히_문서화한다() throws Exception {
    String openApiJson =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode root = objectMapper.readTree(openApiJson);
    JsonNode paths = root.path("paths");
    if (!paths.has("/api/v1/me/saved-places")) {
      return;
    }

    JsonNode standardResponse =
        paths.path("/api/v1/me/saved-places").path("post").path("responses").path("401");
    if (standardResponse.has("$ref")) {
      String reference = standardResponse.path("$ref").asString();
      standardResponse =
          root.path("components")
              .path("responses")
              .path(reference.substring(reference.lastIndexOf('/') + 1));
    }
    JsonNode standard =
        standardResponse.path("content").path("application/problem+json").path("example");
    org.assertj.core.api.Assertions.assertThat(standard.path("code").asString())
        .isEqualTo("AUTHENTICATION_REQUIRED");
    org.assertj.core.api.Assertions.assertThat(standard.path("status").asInt()).isEqualTo(401);
    org.assertj.core.api.Assertions.assertThat(standard.path("type").asString())
        .isEqualTo("https://api.timing-jeju.com/problems/authentication-required");

    JsonNode nonContributor =
        paths
            .path("/api/v1/me/saved-places")
            .path("post")
            .path("responses")
            .path("409")
            .path("content")
            .path("application/problem+json")
            .path("example");
    org.assertj.core.api.Assertions.assertThat(nonContributor.path("code").asString())
        .isEqualTo("IDEMPOTENCY_PAYLOAD_CONFLICT");
    org.assertj.core.api.Assertions.assertThat(nonContributor.path("status").asInt())
        .isEqualTo(409);
    org.assertj.core.api.Assertions.assertThat(nonContributor.path("type").asString())
        .isEqualTo("https://api.timing-jeju.com/problems/idempotency-payload-conflict");
  }

  @Test
  void OpenAPI는_TraceId_header와_모든_공통_problem_응답의_header_참조를_문서화한다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.headers.TraceId.required").value(true))
        .andExpect(jsonPath("$.components.headers.TraceId.schema.type").value("string"))
        .andExpect(jsonPath("$.components.headers.TraceId.schema.pattern").value("^[0-9a-f]{32}$"))
        .andExpect(
            jsonPath("$.components.responses.*.headers['X-Trace-Id'].$ref").value(hasSize(7)))
        .andExpect(
            jsonPath("$.components.responses.*.headers['X-Trace-Id'].$ref")
                .value(everyItem(is("#/components/headers/TraceId"))));
  }

  @Test
  void OpenAPI에_전역_bearerAuth_보안_계약이_포함된다() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
        .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
  }

  @Test
  void Swagger_UI를_조회할_수_있다() throws Exception {
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
