package com.timingjeju.api.global.externalapi;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

public final class ExternalApiExecutor implements AutoCloseable {

  private static final String ACCEPT_ENCODING = "gzip";

  private final Map<ExternalApiProvider, ExternalApiClientSettings> settings;
  private final ExternalHttpTransport transport;
  private final ExternalApiTimeSource timeSource;
  private final ExternalApiJitter jitter;
  private final ExternalApiMetrics metrics;
  private final ExternalApiResiliencePolicy policy;
  private final Map<ExternalApiOperation, ExternalApiCircuitBreaker> circuits;
  private final ExecutorService bodyReader;

  ExternalApiExecutor(
      Map<ExternalApiProvider, ExternalApiClientSettings> settings,
      ExternalHttpTransport transport,
      ExternalApiTimeSource timeSource,
      ExternalApiJitter jitter,
      MeterRegistry meterRegistry,
      ExternalApiResiliencePolicy policy) {
    this.settings = immutableSettings(settings);
    this.transport = Objects.requireNonNull(transport, "transport는 필수입니다.");
    this.timeSource = Objects.requireNonNull(timeSource, "timeSource는 필수입니다.");
    this.jitter = Objects.requireNonNull(jitter, "jitter는 필수입니다.");
    this.metrics =
        new ExternalApiMetrics(Objects.requireNonNull(meterRegistry, "meterRegistry는 필수입니다."));
    this.policy = Objects.requireNonNull(policy, "policy는 필수입니다.");
    this.circuits = new EnumMap<>(ExternalApiOperation.class);
    for (ExternalApiOperation operation : ExternalApiOperation.values()) {
      circuits.put(operation, new ExternalApiCircuitBreaker(policy, timeSource));
    }
    this.bodyReader = Executors.newVirtualThreadPerTaskExecutor();
  }

  public <T> T execute(ExternalApiRequest request, ExternalApiBodyDecoder<T> decoder) {
    Objects.requireNonNull(request, "request는 필수입니다.");
    Objects.requireNonNull(decoder, "decoder는 필수입니다.");
    long started = timeSource.nanoTime();
    ExternalApiCircuitBreaker circuit = circuits.get(request.operation());
    ExternalApiCircuitBreaker.Permit permit;
    try {
      permit = circuit.acquire();
    } catch (ExternalApiCircuitOpenException failure) {
      record(request.operation(), ExternalApiFailureCode.CIRCUIT_OPEN.metricResult(), started);
      throw new ExternalApiException(ExternalApiFailureCode.CIRCUIT_OPEN);
    }

    try {
      T result = executeWithinDeadline(request, decoder, started);
      circuit.record(permit, false);
      record(request.operation(), "success", started);
      return result;
    } catch (ExternalApiException failure) {
      circuit.record(permit, true);
      record(request.operation(), failure.code().metricResult(), started);
      throw failure;
    } catch (RuntimeException ignored) {
      circuit.record(permit, true);
      record(request.operation(), ExternalApiFailureCode.TRANSPORT_ERROR.metricResult(), started);
      throw new ExternalApiException(ExternalApiFailureCode.TRANSPORT_ERROR);
    }
  }

  private <T> T executeWithinDeadline(
      ExternalApiRequest request, ExternalApiBodyDecoder<T> decoder, long started) {
    ExternalApiClientSettings clientSettings = settings.get(request.operation().provider());
    if (clientSettings == null) {
      throw new ExternalApiException(ExternalApiFailureCode.PROVIDER_NOT_CONFIGURED);
    }
    ExternalHttpRequest httpRequest = prepare(clientSettings, request);
    for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
      Duration remaining = remaining(started);
      if (remaining.isZero() || remaining.isNegative()) {
        throw new ExternalApiException(ExternalApiFailureCode.TOTAL_TIMEOUT);
      }
      try (ExternalHttpResponse response =
          transport.exchange(
              httpRequest,
              minimum(clientSettings.connectTimeout(), remaining),
              minimum(clientSettings.readTimeout(), remaining))) {
        requireRemaining(started);
        int status = response.status();
        if (status >= 300 && status < 400) {
          throw new ExternalApiException(ExternalApiFailureCode.REDIRECT_NOT_ALLOWED);
        }
        if (status < 200 || status >= 300) {
          if (isRetryable(request.method(), status)) {
            if (attempt == policy.maxAttempts()) {
              throw new ExternalApiException(ExternalApiFailureCode.RETRY_EXHAUSTED, status);
            }
            Duration delay = retryDelay(response, attempt);
            response.close();
            sleepWithinDeadline(delay, started);
            continue;
          }
          throw new ExternalApiException(ExternalApiFailureCode.HTTP_STATUS, status);
        }
        requireContentType(request.responseFormat(), response);
        byte[] body = readBody(response, minimum(clientSettings.readTimeout(), remaining(started)));
        requireRemaining(started);
        return decode(decoder, body);
      } catch (ExternalApiException failure) {
        throw failure;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ExternalApiException(ExternalApiFailureCode.TRANSPORT_ERROR);
      } catch (IOException failure) {
        ExternalApiFailureCode code = classifyIo(failure);
        if (request.method() == ExternalApiHttpMethod.GET
            && code == ExternalApiFailureCode.CONNECTION_RESET) {
          if (attempt == policy.maxAttempts()) {
            throw new ExternalApiException(ExternalApiFailureCode.RETRY_EXHAUSTED);
          }
          sleepWithinDeadline(policy.retryDelay(attempt, jitter), started);
          continue;
        }
        throw new ExternalApiException(code);
      }
    }
    throw new ExternalApiException(ExternalApiFailureCode.RETRY_EXHAUSTED);
  }

  private ExternalHttpRequest prepare(
      ExternalApiClientSettings clientSettings, ExternalApiRequest request) {
    if (clientSettings.provider() != request.operation().provider()) {
      throw new ExternalApiException(ExternalApiFailureCode.PROVIDER_NOT_CONFIGURED);
    }
    URI target = targetUri(clientSettings, request);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(
        "Accept",
        request.responseFormat() == ExternalApiResponseFormat.JSON
            ? "application/json"
            : "application/xml, text/xml");
    headers.put("Accept-Encoding", ACCEPT_ENCODING);
    if (clientSettings.credential().placement() == ExternalApiCredentialPlacement.HEADER_API_KEY) {
      headers.put("appKey", clientSettings.credential().headerValue());
    }
    return new ExternalHttpRequest(clientSettings.provider(), request.method(), target, headers);
  }

  private static URI targetUri(
      ExternalApiClientSettings clientSettings, ExternalApiRequest request) {
    URI base = clientSettings.baseUrl();
    String basePath = base.getRawPath();
    if (basePath == null || "/".equals(basePath)) {
      basePath = "";
    } else if (basePath.endsWith("/")) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    StringBuilder value =
        new StringBuilder()
            .append(base.getScheme())
            .append("://")
            .append(base.getRawAuthority())
            .append(basePath)
            .append('/')
            .append(request.relativePath());
    List<String> query = new ArrayList<>();
    if (clientSettings.credential().placement()
        == ExternalApiCredentialPlacement.QUERY_SERVICE_KEY) {
      query.add("serviceKey=" + clientSettings.credential().encodedQueryValue());
    }
    request
        .queryParameters()
        .forEach((name, item) -> query.add(percentEncode(name) + "=" + percentEncode(item)));
    if (!query.isEmpty()) {
      value.append('?').append(String.join("&", query));
    }
    URI target = URI.create(value.toString());
    if (!sameOrigin(base, target)
        || !target.getPath().startsWith(basePath.isEmpty() ? "/" : basePath + "/")) {
      throw new IllegalArgumentException("외부 API target이 설정된 base URL 범위를 벗어났습니다.");
    }
    return target;
  }

  private static boolean sameOrigin(URI base, URI target) {
    return base.getScheme().equalsIgnoreCase(target.getScheme())
        && base.getHost().equalsIgnoreCase(target.getHost())
        && effectivePort(base) == effectivePort(target)
        && target.getUserInfo() == null
        && target.getFragment() == null;
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String percentEncode(String value) {
    StringBuilder encoded = new StringBuilder(value.length());
    for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
      int current = item & 0xff;
      if ((current >= 'a' && current <= 'z')
          || (current >= 'A' && current <= 'Z')
          || (current >= '0' && current <= '9')
          || current == '-'
          || current == '.'
          || current == '_'
          || current == '~') {
        encoded.append((char) current);
      } else {
        encoded.append('%');
        encoded.append(Character.toUpperCase(Character.forDigit(current >>> 4, 16)));
        encoded.append(Character.toUpperCase(Character.forDigit(current & 0xf, 16)));
      }
    }
    return encoded.toString();
  }

  private static boolean isRetryable(ExternalApiHttpMethod method, int status) {
    return method == ExternalApiHttpMethod.GET
        && (status == 408 || status == 429 || status == 502 || status == 503 || status == 504);
  }

  private Duration retryDelay(ExternalHttpResponse response, int retryNumber) {
    return response
        .firstHeader("Retry-After")
        .flatMap(this::parseRetryAfter)
        .orElseGet(() -> policy.retryDelay(retryNumber, jitter));
  }

  private java.util.Optional<Duration> parseRetryAfter(String value) {
    String normalized = value.trim();
    try {
      long seconds = Long.parseLong(normalized);
      if (seconds < 0) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(minimum(Duration.ofSeconds(seconds), policy.retryAfterCap()));
    } catch (NumberFormatException ignored) {
      try {
        Instant retryAt =
            ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        Duration delay = Duration.between(timeSource.now(), retryAt);
        if (delay.isNegative()) {
          delay = Duration.ZERO;
        }
        return java.util.Optional.of(minimum(delay, policy.retryAfterCap()));
      } catch (DateTimeParseException invalid) {
        return java.util.Optional.empty();
      }
    }
  }

  private static void requireContentType(
      ExternalApiResponseFormat expected, ExternalHttpResponse response) {
    String contentType = response.firstHeader("Content-Type").orElse("");
    if (!expected.supports(contentType)) {
      throw new ExternalApiException(ExternalApiFailureCode.UNSUPPORTED_CONTENT_TYPE);
    }
  }

  private byte[] readBody(ExternalHttpResponse response, Duration timeout) throws IOException {
    String contentEncoding =
        response.firstHeader("Content-Encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
    Future<byte[]> future =
        bodyReader.submit(
            () -> {
              InputStream input = response.body();
              if ("gzip".equals(contentEncoding)) {
                try {
                  input = new GZIPInputStream(input);
                } catch (ZipException malformed) {
                  throw new MalformedBodyIOException();
                }
              } else if (!(contentEncoding.isEmpty() || "identity".equals(contentEncoding))) {
                throw new UnsupportedEncodingIOException();
              }
              try {
                return readLimited(input, policy.maximumDecompressedBodyBytes());
              } catch (ZipException malformed) {
                throw new MalformedBodyIOException();
              }
            });
    try {
      return future.get(Math.max(1L, timeout.toNanos()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      future.cancel(true);
      throw new HttpTimeoutException("external API body read timeout");
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("external API body read interrupted");
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof BodyTooLargeIOException) {
        throw new ExternalApiException(ExternalApiFailureCode.RESPONSE_TOO_LARGE);
      }
      if (cause instanceof UnsupportedEncodingIOException) {
        throw new ExternalApiException(ExternalApiFailureCode.UNSUPPORTED_CONTENT_ENCODING);
      }
      if (cause instanceof MalformedBodyIOException) {
        throw new ExternalApiException(ExternalApiFailureCode.MALFORMED_RESPONSE);
      }
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("external API body read failed");
    }
  }

  private static byte[] readLimited(InputStream input, long maximum) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    long total = 0L;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maximum) {
        throw new BodyTooLargeIOException();
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static <T> T decode(ExternalApiBodyDecoder<T> decoder, byte[] body) {
    try {
      return decoder.decode(body);
    } catch (Exception failure) {
      throw new ExternalApiException(ExternalApiFailureCode.MALFORMED_RESPONSE);
    }
  }

  private static ExternalApiFailureCode classifyIo(IOException failure) {
    if (failure instanceof HttpConnectTimeoutException) {
      return ExternalApiFailureCode.CONNECT_TIMEOUT;
    }
    if (failure instanceof HttpTimeoutException || failure instanceof SocketTimeoutException) {
      return ExternalApiFailureCode.READ_TIMEOUT;
    }
    if (isConnectionReset(failure)) {
      return ExternalApiFailureCode.CONNECTION_RESET;
    }
    if (failure instanceof ConnectException) {
      return ExternalApiFailureCode.CONNECT_FAILURE;
    }
    return ExternalApiFailureCode.TRANSPORT_ERROR;
  }

  private static boolean isConnectionReset(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SocketException
          && current.getMessage() != null
          && current.getMessage().toLowerCase(Locale.ROOT).contains("connection reset")) {
        return true;
      }
      if (current instanceof IOException
          && current.getMessage() != null
          && current.getMessage().toLowerCase(Locale.ROOT).contains("connection reset")) {
        return true;
      }
    }
    return false;
  }

  private void sleepWithinDeadline(Duration delay, long started) {
    Duration remaining = remaining(started);
    if (delay.compareTo(remaining) >= 0) {
      throw new ExternalApiException(ExternalApiFailureCode.TOTAL_TIMEOUT);
    }
    try {
      timeSource.sleep(delay);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new ExternalApiException(ExternalApiFailureCode.TRANSPORT_ERROR);
    }
  }

  private void requireRemaining(long started) {
    if (remaining(started).isZero() || remaining(started).isNegative()) {
      throw new ExternalApiException(ExternalApiFailureCode.TOTAL_TIMEOUT);
    }
  }

  private Duration remaining(long started) {
    long elapsed = Math.max(0L, timeSource.nanoTime() - started);
    long total = policy.totalTimeout().toNanos();
    return elapsed >= total ? Duration.ZERO : Duration.ofNanos(total - elapsed);
  }

  private void record(ExternalApiOperation operation, String result, long started) {
    long elapsed = Math.max(0L, timeSource.nanoTime() - started);
    metrics.record(operation, result, Duration.ofNanos(elapsed));
  }

  private static Duration minimum(Duration first, Duration second) {
    return first.compareTo(second) <= 0 ? first : second;
  }

  private static Map<ExternalApiProvider, ExternalApiClientSettings> immutableSettings(
      Map<ExternalApiProvider, ExternalApiClientSettings> input) {
    EnumMap<ExternalApiProvider, ExternalApiClientSettings> copy =
        new EnumMap<>(ExternalApiProvider.class);
    input.forEach(
        (provider, item) -> {
          if (provider != item.provider() || copy.putIfAbsent(provider, item) != null) {
            throw new IllegalArgumentException("외부 API provider 설정이 중복되거나 일치하지 않습니다.");
          }
        });
    return Map.copyOf(copy);
  }

  @Override
  public void close() {
    bodyReader.close();
  }

  private static final class BodyTooLargeIOException extends IOException {}

  private static final class UnsupportedEncodingIOException extends IOException {}

  private static final class MalformedBodyIOException extends IOException {}
}
