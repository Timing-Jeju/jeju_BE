package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExternalApiExecutorTest {

  private static final String SAFE_KEY = "fixture-key-not-a-real-secret";

  @Test
  void 일시적_503은_GET만_full_jitter로_재시도하고_회복한_결과를_한번_반환한다() {
    Fixture fixture = new Fixture();
    fixture.transport.respond(status(503), json("{\"ok\":true}"));
    fixture.jitter.values.add(150L);

    String result =
        fixture.executor.execute(request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text);

    assertThat(result).isEqualTo("{\"ok\":true}");
    assertThat(fixture.transport.calls).isEqualTo(2);
    assertThat(fixture.time.sleeps).containsExactly(Duration.ofMillis(150));
  }

  @Test
  void retry_status와_connection_reset만_최대_3회_시도한다() {
    for (int status : new int[] {408, 429, 502, 503, 504}) {
      Fixture fixture = new Fixture();
      fixture.transport.respond(status(status), status(status), status(status));
      fixture.jitter.values.addAll(List.of(0L, 0L));

      assertThatThrownBy(
              () ->
                  fixture.executor.execute(
                      request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text))
          .isInstanceOfSatisfying(
              ExternalApiException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(ExternalApiFailureCode.RETRY_EXHAUSTED);
                assertThat(failure.status()).isEqualTo(status);
              });
      assertThat(fixture.transport.calls).as("status=" + status).isEqualTo(3);
    }

    Fixture reset = new Fixture();
    reset.transport.fail(
        new IOException("Connection reset"), new IOException("Connection reset"), json("{}"));
    reset.jitter.values.addAll(List.of(0L, 0L));
    assertThat(
            reset.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text))
        .isEqualTo("{}");
    assertThat(reset.transport.calls).isEqualTo(3);
  }

  @Test
  void POST와_재시도_불가_status는_한번만_호출한다() {
    Fixture post = new Fixture();
    post.transport.respond(status(503));
    assertFailure(post, request(ExternalApiHttpMethod.POST), ExternalApiFailureCode.HTTP_STATUS);
    assertThat(post.transport.calls).isEqualTo(1);

    Fixture clientError = new Fixture();
    clientError.transport.respond(status(400));
    assertFailure(clientError, ExternalApiFailureCode.HTTP_STATUS);
    assertThat(clientError.transport.calls).isEqualTo(1);
  }

  @Test
  void Retry_After는_delta와_HTTP_date를_읽되_최대_5초로_제한한다() {
    Fixture seconds = new Fixture();
    seconds.transport.respond(status(429, Map.of("Retry-After", List.of("20"))), json("{}"));
    assertThat(
            seconds.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text))
        .isEqualTo("{}");
    assertThat(seconds.time.sleeps).containsExactly(Duration.ofSeconds(5));

    Fixture date = new Fixture();
    String retryAt =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            date.time.now().plusSeconds(3).atZone(ZoneOffset.UTC));
    date.transport.respond(status(429, Map.of("Retry-After", List.of(retryAt))), json("{}"));
    assertThat(
            date.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text))
        .isEqualTo("{}");
    assertThat(date.time.sleeps).containsExactly(Duration.ofSeconds(3));
  }

  @Test
  void connect_read_total_timeout을_서로_다른_failure로_분류한다() {
    Fixture connectTimeout = new Fixture();
    connectTimeout.transport.fail(new HttpConnectTimeoutException("fixture connect timeout"));
    assertFailure(connectTimeout, ExternalApiFailureCode.CONNECT_TIMEOUT);

    Fixture connect = new Fixture();
    connect.transport.fail(new ConnectException("fixture connect"));
    assertFailure(connect, ExternalApiFailureCode.CONNECT_FAILURE);

    Fixture read = new Fixture();
    read.transport.fail(new HttpTimeoutException("fixture read"));
    assertFailure(read, ExternalApiFailureCode.READ_TIMEOUT);

    Fixture total = new Fixture();
    total.transport.action(
        request -> {
          total.time.advance(Duration.ofSeconds(8));
          return status(503);
        });
    assertFailure(total, ExternalApiFailureCode.TOTAL_TIMEOUT);
    assertThat(total.transport.calls).isEqualTo(1);
  }

  @Test
  void total_deadline을_넘는_retry_delay는_sleep하거나_다음_호출을_하지_않는다() {
    Fixture fixture = new Fixture();
    fixture.transport.action(
        request -> {
          fixture.time.advance(Duration.ofMillis(7_900));
          return status(503);
        });
    fixture.jitter.values.add(150L);

    assertFailure(fixture, ExternalApiFailureCode.TOTAL_TIMEOUT);
    assertThat(fixture.transport.calls).isEqualTo(1);
    assertThat(fixture.time.sleeps).isEmpty();
  }

  @Test
  void redirect와_허용되지_않은_target은_fail_closed한다() {
    Fixture redirect = new Fixture();
    redirect.transport.respond(status(302, Map.of("Location", List.of("https://evil.example"))));
    assertFailure(redirect, ExternalApiFailureCode.REDIRECT_NOT_ALLOWED);
    assertThat(redirect.transport.calls).isEqualTo(1);

    assertThatThrownBy(
            () ->
                ExternalApiRequest.of(
                    ExternalApiHttpMethod.GET,
                    ExternalApiOperation.TOUR_AREA_BASED_LIST,
                    "https://evil.example/steal",
                    Map.of(),
                    ExternalApiResponseFormat.JSON))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ExternalApiRequest.get(
                    ExternalApiOperation.TOUR_AREA_BASED_LIST,
                    "../admin",
                    Map.of(),
                    ExternalApiResponseFormat.JSON))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void content_type과_malformed_body를_원문_없이_분류한다() {
    Fixture type = new Fixture();
    type.transport.respond(
        response(200, Map.of("Content-Type", List.of("text/html")), "secret body"));
    assertFailure(type, ExternalApiFailureCode.UNSUPPORTED_CONTENT_TYPE);

    Fixture malformed = new Fixture();
    malformed.transport.respond(json("raw-secret-payload"));
    assertThatThrownBy(
            () ->
                malformed.executor.execute(
                    request(ExternalApiHttpMethod.GET),
                    body -> {
                      throw new IllegalArgumentException("parser leaked " + new String(body));
                    }))
        .isInstanceOfSatisfying(
            ExternalApiException.class,
            failure -> {
              assertThat(failure.code()).isEqualTo(ExternalApiFailureCode.MALFORMED_RESPONSE);
              assertThat(failure.getMessage())
                  .doesNotContain("raw-secret-payload", "parser leaked");
            });
  }

  @Test
  void decompressed_body가_2MiB를_넘으면_streaming중_즉시_중단한다() throws IOException {
    byte[] oversized = new byte[(2 * 1024 * 1024) + 1];
    Fixture plain = new Fixture();
    plain.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new ByteArrayInputStream(oversized)));
    assertFailure(plain, ExternalApiFailureCode.RESPONSE_TOO_LARGE);

    Fixture gzip = new Fixture();
    gzip.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of(
                "Content-Type", List.of("application/json"),
                "Content-Encoding", List.of("gzip")),
            new ByteArrayInputStream(gzip(oversized))));
    assertFailure(gzip, ExternalApiFailureCode.RESPONSE_TOO_LARGE);
  }

  @Test
  void 손상된_gzip과_지원하지_않는_content_encoding은_분류_오류다() {
    Fixture malformed = new Fixture();
    malformed.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of(
                "Content-Type", List.of("application/json"),
                "Content-Encoding", List.of("gzip")),
            new ByteArrayInputStream("not-gzip".getBytes(StandardCharsets.UTF_8))));
    assertFailure(malformed, ExternalApiFailureCode.MALFORMED_RESPONSE);

    Fixture unsupported = new Fixture();
    unsupported.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of(
                "Content-Type", List.of("application/json"),
                "Content-Encoding", List.of("br")),
            new ByteArrayInputStream(new byte[0])));
    assertFailure(unsupported, ExternalApiFailureCode.UNSUPPORTED_CONTENT_ENCODING);
  }

  @Test
  void body_stream이_read_timeout안에_끝나지_않으면_중단한다() {
    Fixture fixture = new Fixture(Duration.ofMillis(50));
    fixture.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new InterruptibleBlockingInputStream()));

    assertFailure(fixture, ExternalApiFailureCode.READ_TIMEOUT);
  }

  @Test
  void 요청과_오류는_query_header_secret과_raw_payload를_노출하지_않는다() {
    Fixture fixture = new Fixture();
    fixture.transport.respond(response(500, Map.of(), "provider-raw-secret"));
    ExternalApiRequest request =
        ExternalApiRequest.get(
            ExternalApiOperation.TOUR_AREA_BASED_LIST,
            "areaBasedList2",
            Map.of("keyword", "private-value"),
            ExternalApiResponseFormat.JSON);

    assertThat(request.toString()).doesNotContain("private-value");
    assertThatThrownBy(() -> fixture.executor.execute(request, ExternalApiExecutorTest::text))
        .isInstanceOfSatisfying(
            ExternalApiException.class,
            failure ->
                assertThat(failure.toString())
                    .doesNotContain(SAFE_KEY, "private-value", "provider-raw-secret"));
  }

  @Test
  void 외부_분류_예외는_transport_body_runtime_원인을_보존하거나_노출하지_않는다() {
    String transportSensitiveText = "serviceKey=redacted-fixture&query=private";
    Fixture transport = new Fixture();
    transport.transport.fail(new IOException(transportSensitiveText));

    assertSanitizedFailure(
        () ->
            transport.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.TRANSPORT_ERROR,
        transportSensitiveText);

    String bodySensitiveText = "Authorization: Bearer redacted-fixture raw-payload";
    Fixture body = new Fixture();
    body.transport.respond(
        new ExternalHttpResponse(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new SensitiveFailingInputStream(bodySensitiveText)));

    assertSanitizedFailure(
        () ->
            body.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.TRANSPORT_ERROR,
        bodySensitiveText);

    String runtimeSensitiveText = "runtime credential query=private-token";
    Fixture runtime = new Fixture();
    runtime.transport.runtimeFailure(new IllegalStateException(runtimeSensitiveText));

    assertSanitizedFailure(
        () ->
            runtime.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.TRANSPORT_ERROR,
        runtimeSensitiveText);
  }

  @Test
  void primary_분류_오류뒤_close_실패는_suppressed와_stack_trace에_남지_않는다() {
    String closeSensitiveText =
        "serviceKey=close-fixture Authorization: Bearer close-fixture raw-payload";

    Fixture status = new Fixture();
    status.transport.respond(
        responseWithBody(
            400, Map.of(), new SensitiveCloseInputStream("ignored", closeSensitiveText)));
    assertSanitizedFailure(
        () ->
            status.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.HTTP_STATUS,
        closeSensitiveText);

    Fixture contentType = new Fixture();
    contentType.transport.respond(
        responseWithBody(
            200,
            Map.of("Content-Type", List.of("text/html")),
            new SensitiveCloseInputStream("raw payload", closeSensitiveText)));
    assertSanitizedFailure(
        () ->
            contentType.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.UNSUPPORTED_CONTENT_TYPE,
        closeSensitiveText);

    Fixture malformed = new Fixture();
    malformed.transport.respond(
        responseWithBody(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new SensitiveCloseInputStream("raw payload", closeSensitiveText)));
    assertSanitizedFailure(
        () ->
            malformed.executor.execute(
                request(ExternalApiHttpMethod.GET),
                body -> {
                  throw new IllegalArgumentException("malformed fixture");
                }),
        ExternalApiFailureCode.MALFORMED_RESPONSE,
        closeSensitiveText);
  }

  @Test
  void close_only와_body_read_close_실패는_안전하게_분류하고_정상_response는_close한다() {
    String closeSensitiveText = "query=private-close raw-payload";
    Fixture closeOnly = new Fixture();
    closeOnly.transport.respond(
        responseWithBody(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new SensitiveCloseInputStream("{}", closeSensitiveText)));
    assertSanitizedFailure(
        () ->
            closeOnly.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.TRANSPORT_ERROR,
        closeSensitiveText);

    String readSensitiveText = "Authorization: Bearer read-fixture raw-payload";
    Fixture readAndClose = new Fixture();
    readAndClose.transport.respond(
        responseWithBody(
            200,
            Map.of("Content-Type", List.of("application/json")),
            new SensitiveReadAndCloseInputStream(readSensitiveText, closeSensitiveText)));
    assertSanitizedFailure(
        () ->
            readAndClose.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text),
        ExternalApiFailureCode.TRANSPORT_ERROR,
        readSensitiveText,
        closeSensitiveText);

    Fixture normal = new Fixture();
    TrackingInputStream tracking = new TrackingInputStream("{}");
    normal.transport.respond(
        responseWithBody(200, Map.of("Content-Type", List.of("application/json")), tracking));
    assertThat(
            normal.executor.execute(
                request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text))
        .isEqualTo("{}");
    assertThat(tracking.closed).isTrue();
  }

  @Test
  void 공공데이터_key와_query는_UTF8로_정확히_한번_encoding한다() {
    Fixture fixture = new Fixture(Duration.ofSeconds(5), "fixture+/=");
    fixture.transport.respond(json("{}"));
    ExternalApiRequest request =
        ExternalApiRequest.get(
            ExternalApiOperation.TOUR_AREA_BASED_LIST,
            "areaBasedList2",
            Map.of("keyword", "제주 바다"),
            ExternalApiResponseFormat.JSON);

    fixture.executor.execute(request, ExternalApiExecutorTest::text);

    assertThat(fixture.transport.lastRequest.uri().getRawQuery())
        .contains("serviceKey=fixture%2B%2F%3D", "keyword=%EC%A0%9C%EC%A3%BC%20%EB%B0%94%EB%8B%A4")
        .doesNotContain("%252B");
    assertThat(fixture.transport.lastRequest.toString())
        .doesNotContain("fixture", "serviceKey", "keyword");
  }

  @Test
  void metric은_고정된_provider_service_operation_result_tag와_latency만_기록한다() {
    Fixture fixture = new Fixture();
    fixture.transport.respond(json("{}"), json("{}"));

    fixture.executor.execute(request(ExternalApiHttpMethod.GET), ExternalApiExecutorTest::text);
    fixture.executor.execute(
        ExternalApiRequest.get(
            ExternalApiOperation.TOUR_AREA_BASED_LIST,
            "areaBasedList2",
            Map.of("keyword", "another-private-value"),
            ExternalApiResponseFormat.JSON),
        ExternalApiExecutorTest::text);

    assertThat(
            fixture
                .registry
                .find("timingjeju.external.api.requests")
                .tags(
                    "provider", "tour_api",
                    "service", "kor_service_2",
                    "operation", "area_based_list",
                    "result", "success")
                .timer())
        .isNotNull();
    assertThat(fixture.registry.getMeters()).hasSize(1);
    assertThat(
            fixture
                .registry
                .find("timingjeju.external.api.requests")
                .tags("operation", "area_based_list")
                .timer()
                .count())
        .isEqualTo(2);
    assertThat(fixture.registry.getMeters().getFirst().getId().getTags())
        .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("private", SAFE_KEY));
  }

  private static ExternalApiRequest request(ExternalApiHttpMethod method) {
    return ExternalApiRequest.of(
        method,
        ExternalApiOperation.TOUR_AREA_BASED_LIST,
        "areaBasedList2",
        Map.of("numOfRows", "10"),
        ExternalApiResponseFormat.JSON);
  }

  private static void assertFailure(Fixture fixture, ExternalApiFailureCode code) {
    assertFailure(fixture, request(ExternalApiHttpMethod.GET), code);
  }

  private static void assertFailure(
      Fixture fixture, ExternalApiRequest request, ExternalApiFailureCode code) {
    assertThatThrownBy(() -> fixture.executor.execute(request, ExternalApiExecutorTest::text))
        .isInstanceOfSatisfying(
            ExternalApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private static void assertSanitizedFailure(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
      ExternalApiFailureCode code,
      String... sensitiveValues) {
    assertThatThrownBy(invocation)
        .isInstanceOfSatisfying(
            ExternalApiException.class,
            failure -> {
              StringWriter stackTrace = new StringWriter();
              failure.printStackTrace(new PrintWriter(stackTrace));

              assertThat(failure.code()).isEqualTo(code);
              assertThat(failure.getCause()).isNull();
              assertThat(failure.getSuppressed()).isEmpty();
              assertThat(failure.getMessage()).doesNotContain(sensitiveValues);
              assertThat(failure.toString()).doesNotContain(sensitiveValues);
              assertThat(stackTrace.toString()).doesNotContain(sensitiveValues);
            });
  }

  private static ExternalHttpResponse json(String body) {
    return response(200, Map.of("Content-Type", List.of("application/json; charset=utf-8")), body);
  }

  private static ExternalHttpResponse status(int status) {
    return status(status, Map.of());
  }

  private static ExternalHttpResponse status(int status, Map<String, List<String>> headers) {
    return response(status, headers, "ignored");
  }

  private static ExternalHttpResponse response(
      int status, Map<String, List<String>> headers, String body) {
    return responseWithBody(
        status, headers, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }

  private static ExternalHttpResponse responseWithBody(
      int status, Map<String, List<String>> headers, InputStream body) {
    return new ExternalHttpResponse(status, headers, body);
  }

  private static byte[] gzip(byte[] body) throws IOException {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
      gzip.write(body);
    }
    return output.toByteArray();
  }

  private static String text(byte[] body) {
    return new String(body, StandardCharsets.UTF_8);
  }

  private static final class Fixture {
    private final ScriptedTransport transport = new ScriptedTransport();
    private final FakeTimeSource time = new FakeTimeSource();
    private final FakeJitter jitter = new FakeJitter();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExternalApiExecutor executor;

    private Fixture() {
      this(Duration.ofSeconds(5));
    }

    private Fixture(Duration readTimeout) {
      this(readTimeout, SAFE_KEY);
    }

    private Fixture(Duration readTimeout, String credential) {
      ExternalApiClientSettings settings =
          new ExternalApiClientSettings(
              ExternalApiProvider.TOUR_API,
              ExternalApiCredential.from(ExternalApiProvider.TOUR_API, credential),
              URI.create("https://apis.data.go.kr/B551011/KorService2"),
              Duration.ofSeconds(2),
              readTimeout);
      executor =
          new ExternalApiExecutor(
              Map.of(ExternalApiProvider.TOUR_API, settings),
              transport,
              time,
              jitter,
              registry,
              ExternalApiResiliencePolicy.defaults());
    }
  }

  @FunctionalInterface
  private interface TransportAction {
    ExternalHttpResponse run(ExternalHttpRequest request) throws IOException;
  }

  private static final class ScriptedTransport implements ExternalHttpTransport {
    private final Deque<TransportAction> actions = new ArrayDeque<>();
    private int calls;
    private ExternalHttpRequest lastRequest;

    private void respond(ExternalHttpResponse... responses) {
      for (ExternalHttpResponse response : responses) {
        actions.add(request -> response);
      }
    }

    private void fail(Object... results) {
      for (Object result : results) {
        if (result instanceof IOException exception) {
          actions.add(
              request -> {
                throw exception;
              });
        } else {
          actions.add(request -> (ExternalHttpResponse) result);
        }
      }
    }

    private void action(TransportAction action) {
      actions.add(action);
    }

    private void runtimeFailure(RuntimeException failure) {
      actions.add(
          request -> {
            throw failure;
          });
    }

    @Override
    public ExternalHttpResponse exchange(
        ExternalHttpRequest request, Duration connectTimeout, Duration responseTimeout)
        throws IOException {
      calls++;
      lastRequest = request;
      return actions.removeFirst().run(request);
    }
  }

  private static final class SensitiveFailingInputStream extends InputStream {

    private final String sensitiveValue;

    private SensitiveFailingInputStream(String sensitiveValue) {
      this.sensitiveValue = sensitiveValue;
    }

    @Override
    public int read() throws IOException {
      throw new IOException(sensitiveValue);
    }
  }

  private static final class SensitiveCloseInputStream extends ByteArrayInputStream {

    private final String sensitiveValue;

    private SensitiveCloseInputStream(String body, String sensitiveValue) {
      super(body.getBytes(StandardCharsets.UTF_8));
      this.sensitiveValue = sensitiveValue;
    }

    @Override
    public void close() throws IOException {
      throw new IOException(sensitiveValue);
    }
  }

  private static final class SensitiveReadAndCloseInputStream extends InputStream {

    private final String readSensitiveValue;
    private final String closeSensitiveValue;

    private SensitiveReadAndCloseInputStream(
        String readSensitiveValue, String closeSensitiveValue) {
      this.readSensitiveValue = readSensitiveValue;
      this.closeSensitiveValue = closeSensitiveValue;
    }

    @Override
    public int read() throws IOException {
      throw new IOException(readSensitiveValue);
    }

    @Override
    public void close() throws IOException {
      throw new IOException(closeSensitiveValue);
    }
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private TrackingInputStream(String body) {
      super(body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class InterruptibleBlockingInputStream extends InputStream {
    @Override
    public int read() throws IOException {
      try {
        Thread.sleep(Duration.ofMinutes(1));
        return -1;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new SocketTimeoutException("fixture body interrupted");
      }
    }
  }

  private static final class FakeTimeSource implements ExternalApiTimeSource {
    private Instant instant = Instant.parse("2026-08-12T00:00:00Z");
    private long nanos;
    private final Deque<Duration> sleeps = new ArrayDeque<>();

    @Override
    public Instant now() {
      return instant;
    }

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
      advance(duration);
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
      nanos += duration.toNanos();
    }
  }

  private static final class FakeJitter implements ExternalApiJitter {
    private final Deque<Long> values = new ArrayDeque<>();

    @Override
    public long nextLong(long exclusiveUpperBound) {
      return values.isEmpty() ? 0 : values.removeFirst();
    }
  }
}
