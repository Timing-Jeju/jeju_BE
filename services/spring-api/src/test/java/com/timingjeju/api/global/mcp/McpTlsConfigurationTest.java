package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import java.net.http.HttpClient;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class McpTlsConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              context ->
                  context.getBeanFactory().setConversionService(new ApplicationConversionService()))
          .withUserConfiguration(McpPrivateClientConfiguration.class)
          .withBean(JsonMapper.class, JsonMapper::new)
          .withPropertyValues(
              "app.mcp.base-url=https://timing-jeju-ai:8443",
              "app.mcp.allowed-host=timing-jeju-ai",
              "app.mcp.issuer=fixture-be",
              "app.mcp.audience=fixture-mcp",
              "app.mcp.subject=backend-worker",
              "app.mcp.scope=jeju:mcp:invoke",
              "app.mcp.signing-key-descriptor-file=/fixture/not-read-until-request.json");

  @Test
  void 비활성이면_잘못된_TLS_파일도_읽거나_HTTP_클라이언트를_생성하지_않는다() {
    runner
        .withPropertyValues(
            "app.mcp.enabled=false", "app.mcp.tls-trust-certificate-file=/missing.pem")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(HttpClient.class);
              assertThat(context).doesNotHaveBean(McpSyncClient.class);
            });
  }

  @Test
  void 활성_TLS_파일_오류는_시작을_거부한다() {
    runner
        .withPropertyValues(
            "app.mcp.enabled=true", "app.mcp.tls-trust-certificate-file=/missing.pem")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("MCP TLS 신뢰 인증서를 읽을 수 없습니다."));
  }

  @Test
  void 일반과_생성_빈에_단일_전용_HTTP_클라이언트를_주입한다() throws Exception {
    SSLContext original = SSLContext.getDefault();
    runner
        .withPropertyValues("app.mcp.enabled=true", "app.schedule-generation.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(HttpClient.class);
              assertThat(context.getBeansOfType(McpSyncClient.class))
                  .containsOnlyKeys(
                      "jejuPlannerMcpSyncClient", "jejuPlannerGenerationMcpSyncClient");
              assertThat(context.getBean(HttpClient.class).sslContext()).isSameAs(original);
            });
    assertThat(SSLContext.getDefault()).isSameAs(original);
  }
}
