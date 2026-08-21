package com.timingjeju.api.global.config;

import com.timingjeju.api.global.error.ApiProblemDetails;
import com.timingjeju.api.global.logging.RequestTraceId;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  private static final String TRACE_ID_HEADER_COMPONENT = "TraceId";
  private static final String TRACE_ID_HEADER_REF =
      "#/components/headers/" + TRACE_ID_HEADER_COMPONENT;

  @Bean
  OpenAPI timingJejuOpenApi() {
    return new OpenAPI()
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .info(
            new Info()
                .title("Timing Jeju API")
                .description("제주 여행 일정과 관광·교통 데이터를 제공하는 공개 API")
                .version("v1"));
  }

  @Bean
  OpenApiCustomizer socialLoginPublicEndpointCustomizer() {
    return openApi -> {
      clearSecurity(openApi, "/api/v1/auth/social/providers");
      clearSecurity(openApi, "/api/v1/auth/social/naver/userinfo");
      optionalSecurity(openApi, "/api/v1/places");
      optionalSecurity(openApi, "/api/v1/places/{placeId}");
    };
  }

  @Bean
  OpenApiCustomizer commonProblemDetailsCustomizer() {
    return openApi -> {
      ModelConverters.getInstance()
          .readAll(ApiProblemDetails.class)
          .forEach(openApi.getComponents()::addSchemas);
      openApi
          .getComponents()
          .addHeaders(
              TRACE_ID_HEADER_COMPONENT,
              new Header()
                  .description("서버가 요청 단위로 생성한 추적 식별자")
                  .required(true)
                  .schema(new StringSchema().pattern("^[0-9a-f]{32}$")));
      openApi
          .getComponents()
          .addResponses("ValidationProblem", problemResponse("요청 값 검증 실패"))
          .addResponses("AuthenticationProblem", problemResponse("인증 실패"))
          .addResponses("AccessDeniedProblem", problemResponse("접근 거부"))
          .addResponses("NotFoundProblem", problemResponse("리소스를 찾을 수 없음"))
          .addResponses("ConflictProblem", problemResponse("현재 상태와 충돌"))
          .addResponses("UpstreamProblem", problemResponse("외부 서비스 오류"))
          .addResponses("InternalServerProblem", problemResponse("안전한 내부 서버 오류"));
      openApi
          .getPaths()
          .values()
          .forEach(
              pathItem ->
                  pathItem
                      .readOperations()
                      .forEach(
                          operation -> {
                            operation
                                .getResponses()
                                .putIfAbsent(
                                    "500",
                                    new ApiResponse()
                                        .$ref("#/components/responses/InternalServerProblem"));
                            if (requiresAuthentication(operation.getSecurity())) {
                              operation
                                  .getResponses()
                                  .putIfAbsent(
                                      "401",
                                      new ApiResponse()
                                          .$ref("#/components/responses/AuthenticationProblem"));
                              operation
                                  .getResponses()
                                  .putIfAbsent(
                                      "403",
                                      new ApiResponse()
                                          .$ref("#/components/responses/AccessDeniedProblem"));
                            }
                            operation.getResponses().values().stream()
                                .filter(response -> response.get$ref() == null)
                                .forEach(OpenApiConfig::addTraceIdHeader);
                          }));
    };
  }

  private static ApiResponse problemResponse(String description) {
    return new ApiResponse()
        .description(description)
        .addHeaderObject(RequestTraceId.TRACE_ID_HEADER, traceIdHeaderReference())
        .content(
            new Content()
                .addMediaType(
                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiProblemDetails"))));
  }

  private static void addTraceIdHeader(ApiResponse response) {
    response.addHeaderObject(RequestTraceId.TRACE_ID_HEADER, traceIdHeaderReference());
  }

  private static Header traceIdHeaderReference() {
    return new Header().$ref(TRACE_ID_HEADER_REF);
  }

  private static void clearSecurity(OpenAPI openApi, String path) {
    if (openApi.getPaths() == null || openApi.getPaths().get(path) == null) {
      return;
    }
    openApi
        .getPaths()
        .get(path)
        .readOperations()
        .forEach(operation -> operation.setSecurity(List.of()));
  }

  private static void optionalSecurity(OpenAPI openApi, String path) {
    if (openApi.getPaths() == null || openApi.getPaths().get(path) == null) {
      return;
    }
    openApi
        .getPaths()
        .get(path)
        .readOperations()
        .forEach(
            operation ->
                operation.setSecurity(
                    List.of(
                        new SecurityRequirement(),
                        new SecurityRequirement().addList("bearerAuth"))));
  }

  private static boolean requiresAuthentication(List<SecurityRequirement> security) {
    return security == null || (!security.isEmpty() && security.stream().noneMatch(Map::isEmpty));
  }
}
