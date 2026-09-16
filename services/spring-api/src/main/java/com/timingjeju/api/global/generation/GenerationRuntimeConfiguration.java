package com.timingjeju.api.global.generation;

import com.timingjeju.api.application.commandinput.CommandInputSnapshotRepository;
import com.timingjeju.api.application.generation.*;
import com.timingjeju.api.domain.generation.adapter.JdbcGenerationCompletionStore;
import com.timingjeju.api.global.mcp.McpGenerationExecutor;
import com.timingjeju.api.global.mcp.McpToolClient;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.schedule-generation", name = "enabled", havingValue = "true")
public class GenerationRuntimeConfiguration {
  /**
   * jeju_AI 6efe58ee78c0c5b203a4106e4b7184348afc289d config/data_sources.toml의 APPROVED ID. 원본
   * SHA-256: 4ddbeaa6011b951a4835fb731fc29e155aab9b084a94ff1d5059d8dd9eb9d1da. 호출 승인 목록이지
   * 원본/geometry 저장 허가가 아니다. 위 baseline에 BE #271 / jeju_AI #22의 승인된 kac.airport만 추가한다. 공개 MCP
   * Schema는 AI 생성본만 사용한다.
   */
  static final Set<String> APPROVED_SOURCES =
      Set.of(
          "tourapi.place",
          "kac.airport",
          "tago.bus-route-stops",
          "travel.service-scope-manifest",
          "spatial.jeju-boundary",
          "tourapi.place-intro",
          "tago.bus-stop",
          "transport.stop-identity-map",
          "tago.bus-route",
          "jeju.bus-timetable",
          "holiday.special-day",
          "tago.bus-arrival",
          "tmap.pedestrian",
          "tmap.driving",
          "jeju.taxi-fare-policy",
          "travel.place-entrance-map",
          "travel.place-hours-map",
          "travel.restaurant-dietary-map",
          "jeju.bus-fare-policy");

  @Bean
  @ConditionalOnProperty(
      prefix = "app.schedule-generation.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  GenerationPlanExecutor generationPlanExecutor(
      GenerationTripInputRepository inputs,
      CommandInputSnapshotRepository commands,
      GenerationPlaceResolver places,
      McpToolClient client,
      ObjectMapper mapper,
      Clock clock,
      GenerationDayHistoryRepository history) {
    return new McpGenerationExecutor(
        inputs, commands, places, client, mapper, clock, APPROVED_SOURCES, history);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "app.schedule-generation.worker",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  GenerationCompletionStore generationCompletionStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      GenerationTripInputRepository inputs) {
    return new JdbcGenerationCompletionStore(jdbc, transactions, inputs);
  }
}
