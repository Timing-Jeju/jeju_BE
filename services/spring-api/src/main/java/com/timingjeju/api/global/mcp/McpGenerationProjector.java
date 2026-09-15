package com.timingjeju.api.global.mcp;

import java.util.Map;

/** Schema 검증 후 요청 일치·동적 ID·근거·시간을 검증하고 최소 결과만 추출하는 서버 경계. */
@FunctionalInterface
public interface McpGenerationProjector<T> {
  T validateAndProject(Map<String, Object> structuredContent);
}
