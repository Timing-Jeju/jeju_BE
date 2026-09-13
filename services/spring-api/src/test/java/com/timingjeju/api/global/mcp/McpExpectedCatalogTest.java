package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class McpExpectedCatalogTest {

  @Test
  void 최소_이전_Day_이력과_후보_만료를_지원하는_AI_PR18_계약을_사용한다() {
    var recommend =
        McpExpectedCatalog.load(new ObjectMapper()).tools().get("recommend_jeju_day_trips");

    assertThat(recommend.inputSchemaSha256())
        .isEqualTo("bdee203887eb769eb28906f5aae68378ecc7ffc855f38e8d1abd474f13353852");
    assertThat(recommend.outputSchemaSha256())
        .isEqualTo("2f3a382869601455f97184135e2ab8dbf0ec5127bd43998a0ea12fa83994409e");
  }

  @Test
  void Pydantic이_생성한_v07_manifest는_현재_여섯_도구와_schema_checksum을_고정한다() {
    McpExpectedCatalog catalog = McpExpectedCatalog.load(new ObjectMapper());

    assertThat(catalog.contractVersion()).isEqualTo("0.7.0");
    assertThat(catalog.tools().keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "recommend_jeju_day_trips",
                "evaluate_jeju_day_trip",
                "revalidate_jeju_day_trip",
                "search_jeju_places",
                "inspect_jeju_bus_stop",
                "preview_jeju_transfer"));
    assertThat(catalog.tools().values())
        .allSatisfy(
            tool -> {
              assertThat(tool.inputSchemaSha256()).matches("[0-9a-f]{64}");
              assertThat(tool.outputSchemaSha256()).matches("[0-9a-f]{64}");
            });
  }
}
