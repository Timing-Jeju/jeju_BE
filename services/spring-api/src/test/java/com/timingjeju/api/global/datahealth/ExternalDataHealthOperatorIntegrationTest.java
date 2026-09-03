package com.timingjeju.api.global.datahealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "app.data-health.operator.enabled=true",
      "app.data-health.operator.issuer=https://ops.timing-jeju.invalid",
      "app.data-health.operator.audience=timing-jeju-ops",
      "app.data-health.operator.jwks-url=https://ops.timing-jeju.invalid/.well-known/jwks.json",
      "management.server.port=0"
    })
class ExternalDataHealthOperatorIntegrationTest {
  @LocalServerPort private int applicationPort;
  @LocalManagementPort private int managementPort;
  @MockitoBean private CompletedProviderDataHealthService service;
  @MockitoBean private OpsJwtDecoderHolder opsJwtDecoderHolder;
  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    when(service.collect()).thenReturn(List.of());
    when(opsJwtDecoderHolder.decode("operator-token")).thenReturn(operatorJwt());
    when(opsJwtDecoderHolder.decode("user-token")).thenThrow(new BadJwtException("invalid"));
  }

  @Test
  void 상세_진단은_별도_management_port에서만_operator_JWT로_조회한다() throws Exception {
    HttpResponse<String> missing = get(managementPort, null);
    HttpResponse<String> user = get(managementPort, "user-token");
    HttpResponse<String> operator = get(managementPort, "operator-token");
    HttpResponse<String> application = get(applicationPort, "operator-token");
    HttpResponse<String> publicHealth = getPath(managementPort, "/actuator/health", null);

    assertThat(managementPort).isNotEqualTo(applicationPort);
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(user.statusCode()).isEqualTo(401);
    assertThat(operator.statusCode()).isEqualTo(200);
    assertThat(operator.body())
        .contains("\"status\":\"UP\"")
        .contains("mobility-route", "\"fallbackCode\":\"대체_미사용\"")
        .doesNotContain("metadata", "rawPayload", "token", "query");
    assertThat(application.statusCode()).isIn(401, 403, 404);
    assertThat(publicHealth.statusCode()).isEqualTo(200);
    assertThat(publicHealth.body()).contains("\"status\":\"UP\"").doesNotContain("dependencies");
  }

  private HttpResponse<String> get(int port, String token) throws Exception {
    return getPath(port, "/actuator/externaldatahealth", token);
  }

  private HttpResponse<String> getPath(int port, String path, String token) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Jwt operatorJwt() {
    Instant now = Instant.now();
    return new Jwt(
        "operator-token",
        now,
        now.plusSeconds(300),
        Map.of("alg", "RS256"),
        Map.of(
            "iss",
            "https://ops.timing-jeju.invalid",
            "aud",
            List.of("timing-jeju-ops"),
            "role",
            "operator",
            "exp",
            now.plusSeconds(300)));
  }
}
