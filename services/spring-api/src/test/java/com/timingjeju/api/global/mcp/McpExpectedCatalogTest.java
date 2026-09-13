package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class McpExpectedCatalogTest {

  @Test
  void 이전_Day_이력과_서버_체류시간_출처를_지원하는_AI_계약을_사용한다() {
    var recommend =
        McpExpectedCatalog.load(new ObjectMapper()).tools().get("recommend_jeju_day_trips");

    assertThat(recommend.inputSchemaSha256())
        .isEqualTo("4defe7054c75658dc88b450faff0b4addceac503e4d37c1a3c1f991f1ccf24c7");
    assertThat(recommend.outputSchemaSha256())
        .isEqualTo("b2b6509948001492b90b21aab03f99d1573f16e9adf548a0897d481c19b52b45");
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
