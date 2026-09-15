package com.timingjeju.api.global.mcp;

public record McpProjectedResult<T>(T projection, String mcpInputHash, int attemptCount) {}
