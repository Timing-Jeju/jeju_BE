package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
