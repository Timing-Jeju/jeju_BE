package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class McpTlsTransportTest {
  @TempDir static Path temporary;
  private static SSLContext serverContext;
  private static Path trustFile;
  private static KeyPair jwtKey;
  private final JsonMapper json = new JsonMapper();
  private final AtomicInteger authenticatedRequests = new AtomicInteger();
  private HttpsServer server;

  @BeforeAll
  static void 테스트_전용_TLS와_JWT_키를_임시_디렉터리에_생성한다() throws Exception {
    Path storeFile = temporary.resolve("fixture.p12");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-startdate",
                "2020/01/01 00:00:00",
                "-validity",
                "36500",
                "-storetype",
                "PKCS12",
                "-keystore",
                storeFile.toString(),
                "-storepass",
                "fixture-only",
                "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("테스트 인증서 생성 시간 초과");
    }
    assertThat(process.exitValue()).isZero();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(storeFile)) {
      store.load(input, "fixture-only".toCharArray());
    }
    String pem =
        "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(store.getCertificate("fixture").getEncoded())
            + "\n-----END CERTIFICATE-----\n";
    trustFile = Files.writeString(temporary.resolve("trust.pem"), pem);
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "fixture-only".toCharArray());
    serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keys.getKeyManagers(), null, null);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    jwtKey = generator.generateKeyPair();
  }

  @BeforeEach
  void 로컬_TLS_MCP_서버를_시작한다() throws Exception {
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.createContext(
        "/mcp",
        exchange -> {
          try {
            if (!exchange.getRequestMethod().equals("POST")) {
              exchange.sendResponseHeaders(405, -1);
              return;
            }
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            SignedJWT token = SignedJWT.parse(header.substring("Bearer ".length()));
            if (!token.verify(new RSASSAVerifier((RSAPublicKey) jwtKey.getPublic()))
                || !token.getJWTClaimsSet().getIssuer().equals("fixture-be")
                || !token.getJWTClaimsSet().getAudience().contains("fixture-mcp")
                || !token.getJWTClaimsSet().getStringClaim("scope").equals("jeju:mcp:invoke")) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            authenticatedRequests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            if (!request.has("id")) {
              exchange.sendResponseHeaders(202, -1);
              return;
            }
            Object result =
                switch (request.path("method").asText()) {
                  case "initialize" ->
                      Map.of(
                          "protocolVersion",
                              request.path("params").path("protocolVersion").asText(),
                          "capabilities", Map.of("tools", Map.of()),
                          "serverInfo", Map.of("name", "tls-fixture", "version", "1.0"));
                  case "tools/list" ->
                      Map.of(
                          "tools",
                          java.util.List.of(
                              Map.of("name", "probe", "inputSchema", Map.of("type", "object"))));
                  case "tools/call" ->
                      Map.of(
                          "content",
                          java.util.List.of(Map.of("type", "text", "text", "ok")),
                          "isError",
                          false);
                  default -> Map.of();
                };
            byte[] response =
                json.writeValueAsBytes(
                    Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          } catch (Exception failure) {
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void 로컬_서버를_정리한다() {
    server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 일반과_생성_클라이언트가_전용_TLS와_JWT로_initialize_list_call한다(boolean generation) throws Exception {
    SSLContext original = SSLContext.getDefault();
    try (HttpClient http = McpTlsHttpClient.create(trustFile.toString());
        McpSyncClient client = client(generation, "localhost", http)) {
      client.initialize();
      assertThat(client.listTools().tools())
          .extracting(McpSchema.Tool::name)
          .containsExactly("probe");
      assertThat(client.callTool(new McpSchema.CallToolRequest("probe", Map.of())).isError())
          .isFalse();
      assertThat(authenticatedRequests.get()).isGreaterThanOrEqualTo(3);
    }
    assertThat(SSLContext.getDefault()).isSameAs(original);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 일반과_생성_클라이언트가_미신뢰_인증서를_거부한다(boolean generation) {
    try (HttpClient http = McpTlsHttpClient.create("");
        McpSyncClient client = client(generation, "localhost", http)) {
      assertThatThrownBy(client::initialize)
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("SSLHandshakeException");
      assertThat(authenticatedRequests.get()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 일반과_생성_클라이언트가_신뢰해도_SAN이_다르면_거부한다(boolean generation) {
    try (HttpClient http = McpTlsHttpClient.create(trustFile.toString());
        McpSyncClient client = client(generation, "127.0.0.1", http)) {
      assertThatThrownBy(client::initialize)
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("SSLHandshakeException");
      assertThat(authenticatedRequests.get()).isZero();
    }
  }

  @Test
  void 생성_제한시간_165초_경계를_유지한다() {
    assertThat(McpPrivateClientConfiguration.resolveRequestTimeout(null))
        .isEqualTo(Duration.ofSeconds(165));
    assertThatThrownBy(
            () -> McpPrivateClientConfiguration.resolveRequestTimeout(Duration.ofSeconds(164)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private McpSyncClient client(boolean generation, String host, HttpClient http) {
    McpPrivateProperties properties =
        new McpPrivateProperties(
            true,
            URI.create("https://" + host + ":" + server.getAddress().getPort()),
            host,
            "fixture-be",
            "fixture-mcp",
            "backend-worker",
            "jeju:mcp:invoke",
            null,
            Duration.ofMinutes(2),
            Duration.ofSeconds(5),
            1,
            null,
            null,
            null);
    McpServiceJwtIssuer issuer =
        new McpServiceJwtIssuer(
            "fixture-be",
            "fixture-mcp",
            "backend-worker",
            "jeju:mcp:invoke",
            "fixture",
            (RSAPrivateKey) jwtKey.getPrivate(),
            Duration.ofMinutes(2),
            Clock.systemUTC(),
            UUID::randomUUID);
    var configuration = new McpPrivateClientConfiguration();
    return generation
        ? configuration.jejuPlannerGenerationMcpSyncClient(
            properties, issuer, json, http, Duration.ofSeconds(165))
        : configuration.jejuPlannerMcpSyncClient(properties, issuer, json, http);
  }
}
