package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.global.mcp.JdbcMcpCallAuditWriter;
import com.timingjeju.api.global.mcp.McpCallAudit;
import com.timingjeju.api.global.mcp.McpCallParent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@SpringBootTest
@Import(PostgreSqlTestcontainersConfiguration.class)
@ActiveProfiles("postgresql-integration")
class McpCallLogSchemaIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private JdbcMcpCallAuditWriter auditWriter;

  @Test
  void MCP_call_log는_payload없이_hash_count_status_latency만_보존한다() {
    List<String> columns =
        jdbcTemplate.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'mcp_compute_call_logs'
            order by ordinal_position
            """,
            String.class);

    assertThat(columns)
        .contains(
            "command_input_hash",
            "mcp_input_hash",
            "schema_checksum",
            "request_fact_count",
            "response_fact_count",
            "attempt_no",
            "latency_ms",
            "error_code")
        .doesNotContain(
            "user_id",
            "trip_plan_id",
            "request_payload_redacted",
            "response_payload_redacted",
            "provider",
            "model",
            "error_message");

    String constraints =
        String.join(
            "\n",
            jdbcTemplate.queryForList(
                """
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid = 'public.mcp_compute_call_logs'::regclass
                order by conname
                """,
                String.class));
    assertThat(constraints)
        .contains(
            "recommend_jeju_day_trips",
            "evaluate_jeju_day_trip",
            "revalidate_jeju_day_trip",
            "search_jeju_places",
            "inspect_jeju_bus_stop",
            "preview_jeju_transfer")
        .doesNotContain("generate_day_itinerary", "calculate_feasibility");
  }

  @Test
  void MCP_call_log_writer는_payload없이_두_hash와_stable_error만_기록한다() {
    UUID computeRunId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    McpCallAudit audit =
        new McpCallAudit(
            McpCallParent.forComputeRun(computeRunId),
            "request-integration-0001",
            "inspect_jeju_bus_stop",
            "0.7.0",
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            1,
            2,
            1,
            "contract_invalid",
            7,
            "MCP_CONTRACT_INVALID");

    jdbcTemplate.execute("set session_replication_role = replica");
    try {
      auditWriter.record(audit);
    } finally {
      jdbcTemplate.execute("set session_replication_role = origin");
    }

    assertThat(
            jdbcTemplate.queryForMap(
                "select command_input_hash, mcp_input_hash, status, error_code from mcp_compute_call_logs where request_id = ?",
                audit.requestId()))
        .containsEntry("command_input_hash", "a".repeat(64))
        .containsEntry("mcp_input_hash", "b".repeat(64))
        .containsEntry("status", "contract_invalid")
        .containsEntry("error_code", "MCP_CONTRACT_INVALID");
  }
}
