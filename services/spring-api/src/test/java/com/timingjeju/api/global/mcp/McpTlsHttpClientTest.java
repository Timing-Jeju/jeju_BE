package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class McpTlsHttpClientTest {
  @TempDir Path temporary;

  @Test
  void 미설정이면_JVM_기본_신뢰와_전역_SSL을_그대로_유지한다() throws Exception {
    SSLContext original = SSLContext.getDefault();
    try (HttpClient client = McpTlsHttpClient.create("")) {
      assertThat(client.sslContext()).isSameAs(original);
      assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
      assertThat(client.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
    }
    assertThat(SSLContext.getDefault()).isSameAs(original);
  }

  @Test
  void 명시한_파일이_누락되거나_잘못되면_내용과_경로_없이_실패한다() throws Exception {
    Path empty = Files.createFile(temporary.resolve("empty.pem"));
    Path malformed = Files.writeString(temporary.resolve("malformed.pem"), "sensitive-fixture");
    Path oversized = Files.writeString(temporary.resolve("large.pem"), "x".repeat(65537));
    for (String file :
        new String[] {
          temporary.resolve("missing.pem").toString(),
          empty.toString(),
          malformed.toString(),
          oversized.toString(),
          temporary.toString(),
          "relative.pem"
        }) {
      assertThatThrownBy(() -> McpTlsHttpClient.create(file))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("MCP TLS 신뢰 인증서를 읽을 수 없습니다.")
          .hasNoCause();
    }
  }
}
