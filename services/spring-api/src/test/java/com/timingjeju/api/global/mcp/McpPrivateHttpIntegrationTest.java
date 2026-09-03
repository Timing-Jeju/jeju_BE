package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.support.postgresql.PostgreSqlTestcontainersConfiguration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@SpringBootTest(
    properties = {
      "app.mcp.enabled=true",
      "app.mcp.base-url=https://127.0.0.1:18443",
      "app.mcp.allowed-host=127.0.0.1"
    })
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
@EnabledIfEnvironmentVariable(named = "MCP_LIVE_TEST", matches = "true")
class McpPrivateHttpIntegrationTest {
  private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000010");
  private static final UUID TRIP_PLAN_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
  private static final UUID TRIP_DAY_ID = UUID.fromString("10000000-0000-0000-0000-000000000012");
  private static final UUID SCHEDULE_VERSION_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000013");
  private static final UUID COMPUTE_RUN_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String REQUEST_ID = "request-live-0001";
  private static final String COMMAND_INPUT_HASH = "a".repeat(64);

  @Autowired private McpToolClient client;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void privateMcpProperties(DynamicPropertyRegistry registry) {
    registry.add("app.mcp.issuer", () -> requiredEnvironment("MCP_JWT_ISSUER"));
    registry.add("app.mcp.audience", () -> requiredEnvironment("MCP_JWT_AUDIENCE"));
    registry.add("app.mcp.subject", () -> "backend-worker");
    registry.add("app.mcp.scope", () -> "jeju:mcp:invoke");
    registry.add(
        "app.mcp.signing-key-descriptor-file",
        () -> requiredEnvironment("MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE"));
  }

  @Test
  @Transactional
  void private_TLS와_RS256으로_initialize_list_call을_종단_검증한다() {
    insertComputeRunFixture();

    McpInvocationResult result =
        client.call(
            new McpInvocation(
                "search_jeju_places",
                REQUEST_ID,
                Map.of("request", Map.of("query", "제주", "limit", 1)),
                COMMAND_INPUT_HASH,
                McpCallParent.forComputeRun(COMPUTE_RUN_ID),
                Map.of(),
                Map.of(
                    "place_id", expectedIds("MCP_LIVE_EXPECTED_PLACE_IDS"),
                    "source_id", expectedIds("MCP_LIVE_EXPECTED_SOURCE_IDS"),
                    "publication_id", expectedIds("MCP_LIVE_EXPECTED_PUBLICATION_IDS"),
                    "source_fact_id", expectedIds("MCP_LIVE_EXPECTED_SOURCE_FACT_IDS"))));

    assertThat(client.isReady()).isTrue();
    assertThat(result.structuredContent())
        .containsKey("status")
        .doesNotContainKeys("raw", "geometry", "original_text");

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            """
            select compute_run_id, tool_name, status, error_code,
                   command_input_hash, mcp_input_hash, schema_checksum
            from public.mcp_compute_call_logs
            where request_id = ?
            """,
            REQUEST_ID);
    assertThat(audit)
        .containsEntry("compute_run_id", COMPUTE_RUN_ID)
        .containsEntry("tool_name", "search_jeju_places")
        .containsEntry("status", "succeeded")
        .containsEntry("error_code", null)
        .containsEntry("command_input_hash", COMMAND_INPUT_HASH);
    assertThat(audit.get("mcp_input_hash")).asString().matches("[0-9a-f]{64}");
    assertThat(audit.get("schema_checksum")).asString().matches("[0-9a-f]{64}");
  }

  private void insertComputeRunFixture() {
    jdbcTemplate.update(
        "insert into auth.users (id, email) values (?, ?)", OWNER_ID, "issue-202@test.invalid");
    jdbcTemplate.update(
        "insert into public.user_profiles (id, email) values (?, ?)",
        OWNER_ID,
        "issue-202@test.invalid");
    jdbcTemplate.update(
        """
        insert into public.trip_plans (
          id, user_id, public_token, start_date, end_date, source_mode, data_version
        ) values (?, ?, 'issue-202-live-test', current_date, current_date, 'fixture', 'v1')
        """,
        TRIP_PLAN_ID,
        OWNER_ID);
    jdbcTemplate.update(
        """
        insert into public.trip_days (id, trip_plan_id, day_no, trip_date)
        values (?, ?, 1, current_date)
        """,
        TRIP_DAY_ID,
        TRIP_PLAN_ID);
    jdbcTemplate.update(
        """
        insert into public.trip_schedule_versions (
          id, trip_plan_id, version_no, status, source_type, created_by_user_id
        ) values (?, ?, 1, 'draft', 'initial', ?)
        """,
        SCHEDULE_VERSION_ID,
        TRIP_PLAN_ID,
        OWNER_ID);
    jdbcTemplate.update(
        """
        insert into public.compute_runs (
          id, trip_plan_id, trip_day_id, schedule_version_id, run_type, status,
          input_hash, contract_version, algorithm_version, next_attempt_at
        ) values (?, ?, ?, ?, 'feasibility', 'queued', ?, '0.7.0', ?, now())
        """,
        COMPUTE_RUN_ID,
        TRIP_PLAN_ID,
        TRIP_DAY_ID,
        SCHEDULE_VERSION_ID,
        COMMAND_INPUT_HASH,
        "issue-202-live-test");
  }

  private static String requiredEnvironment(String name) {
    return java.util.Objects.requireNonNull(System.getenv(name), name + " 환경값이 필요합니다.");
  }

  private static Set<String> expectedIds(String name) {
    Set<String> values = Set.of(requiredEnvironment(name).split(",", -1));
    if (values.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException(name + "에는 비어 있지 않은 ID가 필요합니다.");
    }
    return values;
  }
}
