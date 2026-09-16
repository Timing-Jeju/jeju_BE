package com.timingjeju.api.support.postgresql;

final class PostgreSqlTestConnectionBudget {
  static final int HIKARI_PER_CONTEXT = 3;
  static final int CONTEXT_CACHE_LIMIT = 24;
  static final int CACHED_HIKARI_CONNECTIONS = HIKARI_PER_CONTEXT * CONTEXT_CACHE_LIMIT;
  static final int BUDGET_TEST_MANUAL_CONTEXT_CONNECTIONS = 12;
  static final int BUDGET_TEST_PRECEDING_CONNECTIONS = 2;
  static final int LAUNCHER_ADMIN_CONNECTIONS = 1;
  static final int NON_CONTEXT_RESERVE = 16;
  static final int TEST_CONNECTION_BUDGET = CACHED_HIKARI_CONNECTIONS + NON_CONTEXT_RESERVE;
  static final int SAFETY_CEILING = 90;
  static final int POSTGRES_MAX_CONNECTIONS = 100;
  static final int POSTGRES_RESERVED_CONNECTIONS = 3;
  static final int POSTGRES_USABLE_CONNECTIONS =
      POSTGRES_MAX_CONNECTIONS - POSTGRES_RESERVED_CONNECTIONS;

  private PostgreSqlTestConnectionBudget() {}
}
