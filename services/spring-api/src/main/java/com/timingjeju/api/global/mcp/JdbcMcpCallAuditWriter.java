package com.timingjeju.api.global.mcp;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcMcpCallAuditWriter implements McpCallAuditWriter {
  private final JdbcTemplate jdbcTemplate;

  public JdbcMcpCallAuditWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
  }

  @Override
  public void record(McpCallAudit audit) {
    Objects.requireNonNull(audit, "audit는 필수입니다.");
    jdbcTemplate.update(
        """
        insert into public.mcp_compute_call_logs (
          compute_run_id, generation_run_id, schedule_revision_run_id,
          request_id, tool_name, status, contract_version, latency_ms, error_code,
          command_input_hash, mcp_input_hash, schema_checksum,
          request_fact_count, response_fact_count, attempt_no
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        audit.parent().computeRunId(),
        audit.parent().generationRunId(),
        audit.parent().scheduleRevisionRunId(),
        audit.requestId(),
        audit.toolName(),
        audit.status(),
        audit.contractVersion(),
        audit.latencyMs(),
        audit.errorCode(),
        audit.commandInputHash(),
        audit.mcpInputHash(),
        audit.schemaChecksum(),
        audit.requestFactCount(),
        audit.responseFactCount(),
        audit.attemptNo());
  }
}
