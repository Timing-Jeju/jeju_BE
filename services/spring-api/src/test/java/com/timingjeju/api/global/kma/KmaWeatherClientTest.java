package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class KmaWeatherClientTest {

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
}
