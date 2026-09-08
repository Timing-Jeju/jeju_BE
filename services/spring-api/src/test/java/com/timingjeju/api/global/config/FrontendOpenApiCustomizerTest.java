package com.timingjeju.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class FrontendOpenApiCustomizerTest {

  @Test
  void implementation_ready만_canonical_projection을_활성화한다() {
    assertThat(
            FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                catalog(domain("weather-forecast", "ready")), "weather-forecast"))
        .isTrue();
    assertThat(
            FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                catalog(domain("weather-forecast", "not-ready")), "weather-forecast"))
        .isFalse();
  }

  @Test
  void domain_readiness_row가_없으면_명시적_구성오류로_실패한다() {
    assertThatThrownBy(
            () ->
                FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                    catalog(domain("places", "ready")), "weather-forecast"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("weather-forecast")
        .hasMessageContaining("readiness");
  }

  @Test
  void implementation_readiness_구조나_status가_비정상이면_실패한다() {
    Map<String, Object> missingImplementation = domain("weather-forecast", "ready");
    map(missingImplementation.get("readiness")).remove("implementation");

    Map<String, Object> missingStatus = domain("weather-forecast", "ready");
    map(map(missingStatus.get("readiness")).get("implementation")).remove("status");

    for (Map<String, Object> malformed :
        List.of(
            missingImplementation,
            missingStatus,
            domain("weather-forecast", "pending"),
            domain("weather-forecast", 1))) {
      assertThatThrownBy(
              () ->
                  FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                      catalog(malformed), "weather-forecast"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("weather-forecast")
          .hasMessageContaining("implementation readiness");
    }
  }

  @Test
  void domainContracts와_중복_row의_비정상_구조를_fail_closed로_거부한다() {
    Map<String, Object> invalidReadiness = domain("weather-forecast", "ready");
    invalidReadiness.put("readiness", "ready");

    Map<String, Object> invalidImplementation = domain("weather-forecast", "ready");
    map(invalidImplementation.get("readiness")).put("implementation", "ready");

    List<Map<String, Object>> invalidCatalogs =
        List.of(
            Map.of("domainContracts", "ready"),
            Map.of(
                "domainContracts",
                List.of(
                    domain("weather-forecast", "ready"), domain("weather-forecast", "not-ready"))),
            Map.of("domainContracts", List.of(invalidReadiness)),
            Map.of("domainContracts", List.of(invalidImplementation)));

    for (Map<String, Object> malformed : invalidCatalogs) {
      assertThatThrownBy(
              () ->
                  FrontendOpenApiCustomizer.isCanonicalProjectionEnabled(
                      malformed, "weather-forecast"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("weather-forecast")
          .hasMessageContaining("implementation readiness");
    }
  }

  @Test
  void ready_projection은_requiredNull과_nested_schema_type을_보존한다() {
    Map<String, Object> schemas =
        Map.of(
            "Canonical",
            Map.of("requiredNonNull", List.of("id"), "requiredNull", List.of("deletedAt")));

    Schema<?> projected = canonicalSchema("Canonical", schemas);

    assertThat(projected.getType()).isEqualTo("object");
    assertThat(projected.getRequired()).containsExactlyInAnyOrder("id", "deletedAt");
    assertThat(projected.getProperties().get("id").getType()).isEqualTo("string");
    assertThat(projected.getProperties().get("deletedAt").getType()).isEqualTo("null");
  }

  @Test
  void ready_projection은_closed_allOf를_단일_object로_병합한다() {
    Map<String, Object> schemas =
        Map.of(
            "Base",
            Map.of(
                "type",
                "object",
                "required",
                List.of("id"),
                "properties",
                Map.of("id", Map.of("type", "string", "format", "uuid"))),
            "Canonical",
            Map.of(
                "type",
                "object",
                "nullable",
                true,
                "unevaluatedProperties",
                false,
                "allOf",
                List.of(
                    Map.of("$ref", "Base"),
                    Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("items", "choice"),
                        "properties",
                        Map.of(
                            "items",
                            Map.of("type", "array", "items", Map.of("type", "integer")),
                            "choice",
                            Map.of(
                                "oneOf",
                                List.of(Map.of("type", "string"), Map.of("type", "integer"))))))));

    Schema<?> projected = canonicalSchema("Canonical", schemas);

    assertThat(projected.getType()).isEqualTo("object");
    assertThat(projected.getNullable()).isTrue();
    assertThat(projected.getAdditionalProperties()).isEqualTo(false);
    assertThat(projected.getRequired()).containsExactlyInAnyOrder("id", "items", "choice");
    assertThat(projected.getProperties().get("id").getFormat()).isEqualTo("uuid");
    assertThat(projected.getProperties().get("items").getItems().getType()).isEqualTo("integer");
    assertThat(projected.getProperties().get("choice").getOneOf()).hasSize(2);
  }

  @Test
  void ready_projection은_parameter_body_response_error를_투영하고_drift를_거부한다() {
    String key = "POST /api/v1/test/{tripId}";
    Map<String, Object> catalog = Map.of("schemas", Map.of("path", "Path", "body", "Body"));
    Map<String, Object> endpoint =
        Map.of(
            "successSchema",
            "Response",
            "successStatuses",
            List.of(200),
            "errorMatrix",
            Map.of("400", List.of("INVALID_REQUEST")));
    Map<String, Object> schemas =
        Map.of(
            "Path",
            closedObject(
                List.of("tripId"), Map.of("tripId", Map.of("type", "string", "format", "uuid"))),
            "Body",
            closedObject(List.of("name"), Map.of("name", Map.of("type", "string", "minLength", 1))),
            "Response",
            closedObject(List.of("id"), Map.of("id", Map.of("type", "string", "format", "uuid"))));
    OpenAPI openApi = projectionOpenApi(true);

    projectCanonicalOperation(openApi, key, catalog, endpoint, schemas);

    Operation operation = openApi.getPaths().get("/api/v1/test/{tripId}").getPost();
    assertThat(operation.getParameters().getFirst().getSchema().getFormat()).isEqualTo("uuid");
    assertThat(operation.getParameters().getFirst().getRequired()).isTrue();
    assertThat(
            operation
                .getRequestBody()
                .getContent()
                .get("application/json")
                .getSchema()
                .getRequired())
        .containsExactly("name");
    assertThat(
            operation
                .getResponses()
                .get("200")
                .getContent()
                .get("application/json")
                .getSchema()
                .getRequired())
        .containsExactly("id");
    assertThat(operation.getResponses().get("400").getExtensions().get("x-error-codes"))
        .isEqualTo(List.of("INVALID_REQUEST"));

    OpenAPI noContent = projectionOpenApi(true);
    Operation noContentOperation = noContent.getPaths().get("/api/v1/test/{tripId}").getPost();
    noContentOperation.getResponses().remove("200");
    noContentOperation.getResponses().addApiResponse("204", new ApiResponse());
    projectCanonicalOperation(
        noContent,
        key,
        catalog,
        Map.of(
            "successSchema",
            "none",
            "successStatuses",
            List.of(204),
            "errorMatrix",
            Map.of("400", List.of("INVALID_REQUEST"))),
        schemas);
    assertThat(noContentOperation.getResponses().get("204").getContent()).isNull();

    OpenAPI referencedParameter = projectionOpenApi(false);
    referencedParameter
        .getComponents()
        .addParameters("TripId", new Parameter().name("tripId").in("path").schema(new Schema<>()));
    referencedParameter
        .getPaths()
        .get("/api/v1/test/{tripId}")
        .getPost()
        .addParametersItem(new Parameter().$ref("#/components/parameters/TripId"));

    projectCanonicalOperation(referencedParameter, key, catalog, endpoint, schemas);

    assertThat(
            referencedParameter
                .getPaths()
                .get("/api/v1/test/{tripId}")
                .getPost()
                .getParameters()
                .getFirst()
                .getSchema()
                .getFormat())
        .isEqualTo("uuid");

    OpenAPI externalParameter = projectionOpenApi(false);
    externalParameter
        .getPaths()
        .get("/api/v1/test/{tripId}")
        .getPost()
        .addParametersItem(new Parameter().$ref("https://example.com/parameters/trip-id"));
    assertThatThrownBy(
            () -> projectCanonicalOperation(externalParameter, key, catalog, endpoint, schemas))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("외부 parameter ref");

    assertThatThrownBy(
            () ->
                projectCanonicalOperation(
                    projectionOpenApi(false), key, catalog, endpoint, schemas))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical parameter")
        .hasMessageContaining("tripId");
  }

  @Test
  void ready_projection은_canonical_success_status_누락을_거부한다() {
    String key = "POST /api/v1/test/{tripId}";
    Map<String, Object> catalog = Map.of("schemas", Map.of("path", "Path", "body", "Body"));
    Map<String, Object> endpoint =
        Map.of(
            "successSchema",
            "Response",
            "successStatuses",
            List.of(200),
            "errorMatrix",
            Map.of("400", List.of("INVALID_REQUEST")));
    Map<String, Object> schemas =
        Map.of(
            "Path",
            closedObject(List.of("tripId"), Map.of("tripId", Map.of("type", "string"))),
            "Body",
            closedObject(List.of("name"), Map.of("name", Map.of("type", "string"))),
            "Response",
            closedObject(List.of("id"), Map.of("id", Map.of("type", "string"))));

    OpenAPI missingSuccess = projectionOpenApi(true);
    missingSuccess.getPaths().get("/api/v1/test/{tripId}").getPost().getResponses().remove("200");
    assertThatThrownBy(
            () -> projectCanonicalOperation(missingSuccess, key, catalog, endpoint, schemas))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(key)
        .hasMessageContaining("200");
  }

  @Test
  void ready_projection은_canonical_error_status_누락을_거부한다() {
    String key = "POST /api/v1/test/{tripId}";
    Map<String, Object> catalog = Map.of("schemas", Map.of("path", "Path", "body", "Body"));
    Map<String, Object> endpoint =
        Map.of(
            "successSchema",
            "Response",
            "successStatuses",
            List.of(200),
            "errorMatrix",
            Map.of("400", List.of("INVALID_REQUEST")));
    Map<String, Object> schemas =
        Map.of(
            "Path",
            closedObject(List.of("tripId"), Map.of("tripId", Map.of("type", "string"))),
            "Body",
            closedObject(List.of("name"), Map.of("name", Map.of("type", "string"))),
            "Response",
            closedObject(List.of("id"), Map.of("id", Map.of("type", "string"))));
    OpenAPI missingError = projectionOpenApi(true);
    missingError.getPaths().get("/api/v1/test/{tripId}").getPost().getResponses().remove("400");
    assertThatThrownBy(
            () -> projectCanonicalOperation(missingError, key, catalog, endpoint, schemas))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(key)
        .hasMessageContaining("400");
  }

  private static OpenAPI projectionOpenApi(boolean includePathParameter) {
    Operation operation =
        new Operation()
            .requestBody(
                new RequestBody()
                    .content(
                        new Content()
                            .addMediaType(
                                "application/json", new MediaType().schema(new Schema<>()))))
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "200",
                        new ApiResponse()
                            .content(
                                new Content()
                                    .addMediaType(
                                        "application/json",
                                        new MediaType().schema(new Schema<>()))))
                    .addApiResponse("400", new ApiResponse()));
    if (includePathParameter) {
      operation.addParametersItem(new Parameter().name("tripId").in("path").schema(new Schema<>()));
    }
    return new OpenAPI()
        .components(new Components())
        .paths(new Paths().addPathItem("/api/v1/test/{tripId}", new PathItem().post(operation)));
  }

  private static Map<String, Object> closedObject(
      List<String> required, Map<String, Object> properties) {
    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "required",
        required,
        "properties",
        properties);
  }

  private static void projectCanonicalOperation(
      OpenAPI openApi,
      String key,
      Map<String, Object> catalog,
      Map<String, Object> endpoint,
      Map<String, Object> schemas) {
    ReflectionTestUtils.invokeMethod(
        customizer(), "projectCanonicalOperation", openApi, key, catalog, endpoint, schemas);
  }

  private static Schema<?> canonicalSchema(String name, Map<String, Object> schemas) {
    return ReflectionTestUtils.invokeMethod(
        customizer(), "canonicalSchema", name, schemas, "GET /api/v1/test");
  }

  private static FrontendOpenApiCustomizer customizer() {
    return new FrontendOpenApiCustomizer(new ObjectMapper(), null, null, null);
  }

  private static Map<String, Object> catalog(Map<String, Object> domain) {
    return Map.of("domainContracts", List.of(domain));
  }

  private static Map<String, Object> domain(String name, Object status) {
    Map<String, Object> implementation = new LinkedHashMap<>();
    implementation.put("status", status);
    Map<String, Object> readiness = new LinkedHashMap<>();
    readiness.put("implementation", implementation);
    Map<String, Object> domain = new LinkedHashMap<>();
    domain.put("domain", name);
    domain.put("readiness", readiness);
    return domain;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
