package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class KmaWeatherClientTest {

  @Test
  void preservesExactDecompressedBytesForEveryPageAndVersion() {
    byte[] pageOne =
        (" { \"sentinel\" : 1.00, \"response\":{\"body\":{\"totalCount\":1001,"
                + "\"numOfRows\":1000,\"pageNo\":1},\"header\":{\"resultCode\":\"00\"}}}\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] pageTwo =
        ("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":"
                + "{\"pageNo\":2,\"numOfRows\":1000,\"totalCount\":1001}},"
                + "\"numberLexeme\":1e+0}")
            .getBytes(StandardCharsets.UTF_8);
    byte[] version =
        ("\n{\"response\":{\"header\":{\"resultCode\":\"00\"}," + "\"body\":{\"pageNo\":1}}}\n")
            .getBytes(StandardCharsets.UTF_8);
    KmaWeatherClient client =
        new KmaWeatherClient(
            request ->
                request.operation() == ExternalApiOperation.KMA_FORECAST_VERSION
                    ? version
                    : ("2".equals(request.queryParameters().get("pageNo")) ? pageTwo : pageOne));

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(response.parts()).hasSize(3);
    assertThat(response.parts().get(0).payload()).isEqualTo(pageOne);
    assertThat(response.parts().get(1).payload()).isEqualTo(pageTwo);
    assertThat(response.parts().get(2).payload()).isEqualTo(version);
  }

  @Test
  void returnsPageTwoProviderErrorBytesForRejectedAuditBeforeVersionCall() {
    byte[] pageOne = villageEnvelope(1, 1001).getBytes(StandardCharsets.UTF_8);
    byte[] pageTwo =
        villageEnvelope(2, 1001)
            .replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"")
            .getBytes(StandardCharsets.UTF_8);
    List<KmaWeatherHttpRequest> requests = new ArrayList<>();
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              requests.add(request);
              return "2".equals(request.queryParameters().get("pageNo")) ? pageTwo : pageOne;
            });

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(response.parts()).hasSize(2);
    assertThat(response.parts().get(1).payload()).isEqualTo(pageTwo);
    assertThat(requests).noneMatch(request -> request.relativePath().equals("getFcstVersion"));
  }

  @Test
  void returnsForecastVersionProviderErrorBytesForRejectedAudit() {
    byte[] versionError =
        versionEnvelope()
            .replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"")
            .getBytes(StandardCharsets.UTF_8);
    KmaWeatherClient client =
        new KmaWeatherClient(
            request ->
                request.operation() == ExternalApiOperation.KMA_FORECAST_VERSION
                    ? versionError
                    : villageEnvelope(
                            Integer.parseInt(request.queryParameters().get("pageNo")), 1001)
                        .getBytes(StandardCharsets.UTF_8));

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(response.parts()).hasSize(3);
    assertThat(response.parts().getLast().payload()).isEqualTo(versionError);
  }

  @Test
  void fetchesEveryVillagePageAndForecastVersionWithoutCredentialMetadata() {
    List<KmaWeatherHttpRequest> requests = new ArrayList<>();
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              requests.add(request);
              if (request.operation() == ExternalApiOperation.KMA_FORECAST_VERSION) {
                return versionEnvelope().getBytes(StandardCharsets.UTF_8);
              }
              String page = request.queryParameters().get("pageNo");
              return villageEnvelope(Integer.parseInt(page), 1001).getBytes(StandardCharsets.UTF_8);
            });

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(requests).hasSize(3);
    assertThat(requests.subList(0, 2))
        .allSatisfy(
            request -> {
              assertThat(request.operation()).isEqualTo(ExternalApiOperation.KMA_VILLAGE_FORECAST);
              assertThat(request.queryParameters()).doesNotContainKeys("serviceKey", "ServiceKey");
            });
    assertThat(requests.get(0).queryParameters()).containsEntry("pageNo", "1");
    assertThat(requests.get(1).queryParameters()).containsEntry("pageNo", "2");
    assertThat(requests.get(2).operation()).isEqualTo(ExternalApiOperation.KMA_FORECAST_VERSION);
    assertThat(requests.get(2).relativePath()).isEqualTo("getFcstVersion");
    assertThat(requests.get(2).queryParameters())
        .containsEntry("ftype", "SHRT")
        .containsEntry("basedatetime", "202608160500");
    assertThat(response.parts()).hasSize(3);
    assertThat(response.parts())
        .extracting(part -> part.providerOperation())
        .containsExactly("getVilageFcst", "getVilageFcst", "getFcstVersion");
  }

  @Test
  void returnsUnboundedProviderTotalForRejectedAuditBeforeAdditionalCalls() {
    List<KmaWeatherHttpRequest> requests = new ArrayList<>();
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              requests.add(request);
              return villageEnvelope(1, 5001).getBytes(StandardCharsets.UTF_8);
            });

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(response.parts()).singleElement();
    assertThat(requests).hasSize(1);
  }

  @Test
  void returnsFirstPageProviderErrorForRejectedAuditBeforeVersionCall() {
    List<KmaWeatherHttpRequest> requests = new ArrayList<>();
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              requests.add(request);
              return villageEnvelope(1, 16)
                  .replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"")
                  .getBytes(StandardCharsets.UTF_8);
            });

    var response =
        client.fetch(
            KmaWeatherOperation.VILLAGE_FORECAST,
            new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(5, 0)),
            52,
            38);

    assertThat(response.parts()).singleElement();
    assertThat(requests)
        .singleElement()
        .satisfies(request -> assertThat(request.relativePath()).isEqualTo("getVilageFcst"));
  }

  @Test
  void buildsOfficialCurrentAndForecastRequestsWithoutCredentialQuery() {
    AtomicReference<KmaWeatherHttpRequest> captured = new AtomicReference<>();
    KmaWeatherClient client =
        new KmaWeatherClient(
            request -> {
              captured.set(request);
              return "{}".getBytes(StandardCharsets.UTF_8);
            });
    ForecastBaseTime base = new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(0, 30));

    client.fetch(KmaWeatherOperation.ULTRA_FORECAST, base, 52, 38);

    assertThat(captured.get().operation()).isEqualTo(ExternalApiOperation.KMA_ULTRA_FORECAST);
    assertThat(captured.get().relativePath()).isEqualTo("getUltraSrtFcst");
    assertThat(captured.get().queryParameters())
        .containsEntry("dataType", "JSON")
        .containsEntry("pageNo", "1")
        .containsEntry("numOfRows", "1000")
        .containsEntry("base_date", "20260816")
        .containsEntry("base_time", "0030")
        .containsEntry("nx", "52")
        .containsEntry("ny", "38")
        .doesNotContainKeys("serviceKey", "ServiceKey", "apiKey", "Authorization");
    assertThat(captured.get().toString())
        .contains("queryParameters=[REDACTED]")
        .doesNotContain("0030");
  }

  @Test
  void rejectsGridOutsideOfficialDfsBoundsBeforeCallingProvider() {
    KmaWeatherClient client = new KmaWeatherClient(request -> new byte[0]);
    ForecastBaseTime base = new ForecastBaseTime(LocalDate.of(2026, 8, 16), LocalTime.of(0, 0));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.fetch(KmaWeatherOperation.ULTRA_CURRENT, base, 0, 38));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> client.fetch(KmaWeatherOperation.ULTRA_CURRENT, base, 52, 254));
  }

  private static String villageEnvelope(int page, int total) {
    return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"dataType\":\"JSON\","
        + "\"pageNo\":"
        + page
        + ",\"numOfRows\":1000,\"totalCount\":"
        + total
        + ",\"items\":{\"item\":[]}}}}";
  }

  private static String versionEnvelope() {
    return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"dataType\":\"JSON\","
        + "\"pageNo\":1,\"numOfRows\":10,\"totalCount\":1,\"items\":{\"item\":[]}}}}";
  }
}
