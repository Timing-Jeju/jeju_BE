package com.timingjeju.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OpenApiConfigTest {

  private static final String TRACE_HEADER_REF = "#/components/headers/TraceId";

  private final OpenApiConfig config = new OpenApiConfig();

  @Test
  void 보호_operation에는_401_403_500을_추가하고_공개_operation에는_500만_추가한다() {
    Operation protectedOperation = operationWithInlineSuccessResponse();
    Operation publicOperation = operationWithInlineSuccessResponse().security(List.of());
    OpenAPI openApi =
        config
            .timingJejuOpenApi()
            .paths(
                new Paths()
                    .addPathItem("/protected", new PathItem().get(protectedOperation))
                    .addPathItem("/public", new PathItem().get(publicOperation)));

    config.commonProblemDetailsCustomizer().customise(openApi);

    assertResponseRef(protectedOperation, "401", "#/components/responses/AuthenticationProblem");
    assertResponseRef(protectedOperation, "403", "#/components/responses/AccessDeniedProblem");
    assertResponseRef(protectedOperation, "500", "#/components/responses/InternalServerProblem");
    assertThat(publicOperation.getResponses()).doesNotContainKeys("401", "403");
    assertResponseRef(publicOperation, "500", "#/components/responses/InternalServerProblem");
  }

  @Test
  void TraceId_header_component를_공통_problem_응답과_inline_응답에서_참조한다() {
    Operation operation = operationWithInlineSuccessResponse();
    OpenAPI openApi =
        config
            .timingJejuOpenApi()
            .paths(new Paths().addPathItem("/protected", new PathItem().get(operation)));

    config.commonProblemDetailsCustomizer().customise(openApi);

    Header traceId = openApi.getComponents().getHeaders().get("TraceId");
    assertThat(traceId).isNotNull();
    assertThat(traceId.getRequired()).isTrue();
    assertThat(traceId.getSchema().getType()).isEqualTo("string");
    assertThat(traceId.getSchema().getPattern()).isEqualTo("^[0-9a-f]{32}$");
    openApi
        .getComponents()
        .getResponses()
        .values()
        .forEach(
            response ->
                assertThat(response.getHeaders().get("X-Trace-Id").get$ref())
                    .isEqualTo(TRACE_HEADER_REF));
    assertThat(operation.getResponses().get("200").getHeaders().get("X-Trace-Id").get$ref())
        .isEqualTo(TRACE_HEADER_REF);
  }

  private static Operation operationWithInlineSuccessResponse() {
    return new Operation()
        .responses(
            new ApiResponses().addApiResponse("200", new ApiResponse().description("성공 응답")));
  }

  private static void assertResponseRef(Operation operation, String status, String expectedRef) {
    assertThat(operation.getResponses().get(status).get$ref()).isEqualTo(expectedRef);
  }
}
