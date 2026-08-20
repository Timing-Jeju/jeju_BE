package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportCommand;
import com.timingjeju.api.global.tourapi.discovery.TourApiDiscoveryClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class TourApiDiscoveryClientExternalApiBoundaryTest {

  private static final String SERVICE_KEY = "boundary-secret+/=";
  private static final URI OFFICIAL_BASE =
      URI.create("https://apis.data.go.kr/B551011/KorService2");

  @ParameterizedTest(name = "{0}")
  @MethodSource("discoveryRequests")
  void 세_operation은_공식_base와_상대_path로_공통_executor_경계를_통과한다(
      String operation,
      DiscoveryImportCommand command,
      String expectedPublicQueryName,
      String expectedPublicQueryValue) {
    RecordingTransport transport = new RecordingTransport();
    try (ExternalApiExecutor executor = executor(transport)) {
      new TourApiDiscoveryClient(executor).fetch(command, 1);
    }

    URI target = transport.lastRequest.uri();
    assertThat(target.getRawPath()).isEqualTo(OFFICIAL_BASE.getRawPath() + "/" + operation);
    assertThat(target.getRawQuery())
        .contains("serviceKey=boundary-secret%2B%2F%3D")
        .contains("_type=json")
        .contains(expectedPublicQueryName + "=" + expectedPublicQueryValue);
    assertThat(transport.lastRequest.toString())
        .doesNotContain(
            SERVICE_KEY, "serviceKey", expectedPublicQueryName, expectedPublicQueryValue);
  }

  @Test
  void credential은_executor에서만_주입되고_adapter_실패는_secret과_query를_노출하지_않는다() {
    String keyword = "비밀 제주 검색어";
    FailingTransport transport = new FailingTransport(SERVICE_KEY, keyword);

    try (ExternalApiExecutor executor = executor(transport)) {
      TourApiDiscoveryClient client = new TourApiDiscoveryClient(executor);

      assertThatThrownBy(
              () -> client.fetch(DiscoveryImportCommand.keyword(keyword, 1, "keyword"), 1))
          .isInstanceOfSatisfying(
              ExternalApiException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(ExternalApiFailureCode.TRANSPORT_ERROR);
                assertThat(failure.getMessage()).doesNotContain(SERVICE_KEY, keyword, "serviceKey");
                assertThat(failure.toString()).doesNotContain(SERVICE_KEY, keyword, "serviceKey");
              });
    }

    assertThat(transport.lastRequest.uri().getRawQuery())
        .contains("serviceKey=boundary-secret%2B%2F%3D")
        .contains("keyword=%EB%B9%84%EB%B0%80%20%EC%A0%9C%EC%A3%BC%20%EA%B2%80%EC%83%89%EC%96%B4");
    assertThat(transport.lastRequest.toString()).doesNotContain(SERVICE_KEY, keyword, "serviceKey");
  }

  private static Stream<Arguments> discoveryRequests() {
    return Stream.of(
        Arguments.of(
            "locationBasedList2",
            DiscoveryImportCommand.location(126.5, 33.5, 1000, 1, "location"),
            "radius",
            "1000"),
        Arguments.of(
            "searchKeyword2",
            DiscoveryImportCommand.keyword("성산 일출봉", 1, "keyword"),
            "keyword",
            "%EC%84%B1%EC%82%B0%20%EC%9D%BC%EC%B6%9C%EB%B4%89"),
        Arguments.of("searchStay2", DiscoveryImportCommand.stay(1, "stay"), "lDongRegnCd", "50"));
  }

  private static ExternalApiExecutor executor(ExternalHttpTransport transport) {
    ExternalApiClientSettings settings =
        new ExternalApiClientSettings(
            ExternalApiProvider.TOUR_API,
            ExternalApiCredential.from(ExternalApiProvider.TOUR_API, SERVICE_KEY),
            OFFICIAL_BASE,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    return new ExternalApiExecutor(
        Map.of(ExternalApiProvider.TOUR_API, settings),
        transport,
        ExternalApiTimeSource.system(),
        ExternalApiJitter.threadLocal(),
        new SimpleMeterRegistry(),
        ExternalApiResiliencePolicy.defaults());
  }

  private static ExternalHttpResponse jsonResponse() {
    return new ExternalHttpResponse(
        200,
        Map.of("Content-Type", List.of("application/json")),
        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
  }

  private static final class RecordingTransport implements ExternalHttpTransport {
    private ExternalHttpRequest lastRequest;

    @Override
    public ExternalHttpResponse exchange(
        ExternalHttpRequest request, Duration connectTimeout, Duration responseTimeout) {
      lastRequest = request;
      return jsonResponse();
    }
  }

  private static final class FailingTransport implements ExternalHttpTransport {
    private final String serviceKey;
    private final String keyword;
    private ExternalHttpRequest lastRequest;

    private FailingTransport(String serviceKey, String keyword) {
      this.serviceKey = serviceKey;
      this.keyword = keyword;
    }

    @Override
    public ExternalHttpResponse exchange(
        ExternalHttpRequest request, Duration connectTimeout, Duration responseTimeout)
        throws IOException {
      lastRequest = request;
      throw new IOException("failed serviceKey=" + serviceKey + " keyword=" + keyword);
    }
  }
}
