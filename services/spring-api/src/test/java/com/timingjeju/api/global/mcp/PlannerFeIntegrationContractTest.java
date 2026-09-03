package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class PlannerFeIntegrationContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void FE_기준_SHA와_필수_projection_스키마를_고정한다() throws Exception {
    JsonNode contract = readContract();

    assertThat(contract.path("frontend").path("commit").asText())
        .isEqualTo("3c280a1cc05c73150e6f0bb1174443906de04f91");
    assertThat(contract.path("frontend").path("sourceFiles").size()).isEqualTo(11);
    assertThat(contract.path("mcpContractVersion").asText()).isEqualTo("0.7.0");
    assertThat(contract.path("schemas").propertyNames())
        .containsAll(
            Set.of(
                "TripConditions",
                "SchedulePlace",
                "FavoritePlace",
                "DayReview",
                "RouteLeg",
                "RouteStep",
                "RouteBus",
                "FillSuggestion",
                "RouteAlternative"));
  }

  @Test
  void DayReview는_FE_필드만_허용하고_서버_식별자는_wrapper에_둔다() throws Exception {
    JsonNode schemas = readContract().path("schemas");
    JsonNode review = schemas.path("DayReview");
    JsonNode selected = schemas.path("SelectedCandidate");

    assertThat(review.path("additionalProperties").asBoolean()).isFalse();
    assertThat(review.path("properties").propertyNames())
        .containsExactlyInAnyOrder("mode", "summary", "legs", "dirty", "confirmed");
    assertThat(selected.path("properties").has("scheduleVersionId")).isTrue();
    assertThat(review.path("properties").has("scheduleVersionId")).isFalse();
  }

  @Test
  void planned_endpoint는_구현된_API로_표시하지_않는다() throws Exception {
    JsonNode endpoints = readContract().path("endpoints");

    assertThat(endpoints)
        .allSatisfy(
            endpoint -> assertThat(endpoint.path("status").asText()).isIn("existing", "planned"));
    assertThat(endpoints)
        .anySatisfy(
            endpoint -> {
              assertThat(endpoint.path("action").asText()).isEqualTo("generateDay");
              assertThat(endpoint.path("status").asText()).isEqualTo("planned");
            });
  }

  private JsonNode readContract() throws Exception {
    try (InputStream input =
        getClass().getResourceAsStream("/domains/planner-fe-integration/contract.json")) {
      assertThat(input).isNotNull();
      return objectMapper.readTree(input);
    }
  }
}
