package com.timingjeju.api.global.mcp;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

final class McpTlsHttpClient {
  private static final int MAX_CERTIFICATE_BYTES = 65_536;

  private McpTlsHttpClient() {}

  static HttpClient create(String certificateFile) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);
    SSLParameters parameters = new SSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    builder.sslParameters(parameters);
    if (certificateFile != null && !certificateFile.isBlank()) {
      builder.sslContext(loadTrust(certificateFile));
    }
    return builder.build();
  }

  private static SSLContext loadTrust(String certificateFile) {
    try {
      Path path = Path.of(certificateFile);
      if (!path.isAbsolute() || !Files.isRegularFile(path)) {
        throw new IllegalArgumentException();
      }
      byte[] bytes;
      try (var input = Files.newInputStream(path)) {
        bytes = input.readNBytes(MAX_CERTIFICATE_BYTES + 1);
      }
      String pem = new String(bytes, StandardCharsets.US_ASCII);
      if (bytes.length > MAX_CERTIFICATE_BYTES
          || !pem.matches(
              "(?s)(?:\\s*-----BEGIN CERTIFICATE-----\\s*[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*)+")) {
        throw new IllegalArgumentException();
      }
      var certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(bytes));
      if (certificates.isEmpty()) throw new IllegalArgumentException();
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      int index = 0;
      for (var certificate : certificates) {
        ((X509Certificate) certificate).checkValidity();
        trustStore.setCertificateEntry("mcp-" + index++, certificate);
      }
      TrustManagerFactory managers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      managers.init(trustStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, managers.getTrustManagers(), null);
      return context;
    } catch (Exception exception) {
      // Do not include file paths, certificate content or provider exception details.
      throw new IllegalStateException("MCP TLS 신뢰 인증서를 읽을 수 없습니다.");
    }
  }
}
