package com.timingjeju.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.trip.exception.TripPlacePreferencesProblemDefinitions;
import com.timingjeju.api.domain.trip.exception.TripPreferencesProblemDefinitions;
import com.timingjeju.api.domain.weather.exception.WeatherForecastProblemDefinitions;
import com.timingjeju.api.global.error.ProblemCodeRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class FrontendOpenApiReadinessTest {
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void 미구현_날씨_selector는_현행_API_parameter와_response를_덮어쓰지_않는다() {
    OpenAPI api = runtime();
    var operation = api.getPaths().get("/api/v1/weather/forecast").getGet();
    var originalResponse =
        operation.getResponses().get("200").getContent().get("application/json").getSchema();
    customizer("not-ready").customise(api);
    assertThat(operation.getParameters())
        .extracting(Parameter::getName)
        .contains("lat", "lng", "dateTime")
        .doesNotContain("placeId");
    assertThat(operation.getResponses().get("200").getContent().get("application/json").getSchema())
        .isSameAs(originalResponse);
  }

  @Test
  void 구현완료_계약의_parameter_drift는_계속_실패한다() {
    assertThatThrownBy(() -> customizer("ready").customise(runtime()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical parameter");
  }

  @Test
  void 비정상_readiness는_생성_오류로_드러난다() {
    assertThatThrownBy(() -> customizer("unknown").customise(runtime()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 구현완료_계약의_success_response_누락은_실패한다() {
    OpenAPI api = runtime();
    api.getPaths()
        .get("/api/v1/weather/forecast")
        .getGet()
        .addParametersItem(new Parameter().in("query").name("placeId").schema(new StringSchema()));
    api.getPaths().get("/api/v1/weather/forecast").getGet().getResponses().remove("200");
    assertThatThrownBy(() -> customizer("ready").customise(api))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical response");
  }

  private FrontendOpenApiCustomizer customizer(String status) {
    return new FrontendOpenApiCustomizer(
        mapper,
        new ProblemCodeRegistry(List.of(new WeatherForecastProblemDefinitions())),
        new TripPlacePreferencesProblemDefinitions(),
        new TripPreferencesProblemDefinitions(),
        path -> resource(path, status));
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> resource(String path, String status) {
    try (var stream = getClass().getResourceAsStream(path)) {
      Map<String, Object> resource = mapper.readValue(stream, Map.class);
      if (path.equals("/rest/catalog.json")) {
        for (var row : (List<Map<String, Object>>) resource.get("domainContracts")) {
          if (row.get("domain").equals("weather-forecast")) {
            var implementation =
                (Map<String, Object>)
                    ((Map<String, Object>) row.get("readiness")).get("implementation");
            implementation.put("status", status);
            if (status.equals("not-ready")) implementation.put("evidence", null);
          }
        }
      }
      if (path.equals("/domains/weather-forecast/contract.json")) {
        var schemas = (Map<String, Object>) resource.get("schemas");
        schemas.put(
            "WeatherForecastQuery",
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "required",
                List.of("placeId"),
                "properties",
                Map.of("placeId", Map.of("type", "string"))));
      }
      return resource;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static OpenAPI runtime() {
    var operation =
        new Operation()
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "200",
                        new ApiResponse()
                            .description("runtime")
                            .content(
                                new Content()
                                    .addMediaType(
                                        "application/json",
                                        new MediaType().schema(new StringSchema())))));
    for (String name : List.of("lat", "lng", "dateTime")) {
      operation.addParametersItem(
          new Parameter().in("query").name(name).schema(new StringSchema()));
    }
    return new OpenAPI()
        .components(
            new Components().schemas(new LinkedHashMap<>()).responses(new LinkedHashMap<>()))
        .paths(new Paths().addPathItem("/api/v1/weather/forecast", new PathItem().get(operation)));
  }
}
