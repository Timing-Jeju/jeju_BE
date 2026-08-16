package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class KmaVillageForecastJsonParserTest {

  private final KmaWeatherJsonParser parser = new KmaWeatherJsonParser(new ObjectMapper());

  @Test
  void parsesVersionedRepositoryFixtures() throws Exception {
    String forecast = resource("fixtures/kma/get-vilage-fcst.json");
    String version = resource("fixtures/kma/get-fcst-version.json");

    assertThat(
            parser
                .parse(KmaWeatherOperation.VILLAGE_FORECAST, combined(forecast, version))
                .forecasts())
        .hasSize(2)
        .allSatisfy(value -> assertThat(value.forecastVersion()).isEqualTo("202608160500"));
  }

  @Test
  void aggregatesAllVillageCategoriesAndForecastVersionByHourlyValidTime() {
    var parsed =
        parser.parse(
            KmaWeatherOperation.VILLAGE_FORECAST, combined(page(1, 1, items()), version()));

    assertThat(parsed.forecasts()).hasSize(2);
    assertThat(parsed.forecasts().getFirst())
        .satisfies(
            forecast -> {
              assertThat(forecast.forecastType()).isEqualTo("short");
              assertThat(forecast.forecastVersion()).isEqualTo("202608160500");
              assertThat(forecast.forecastedAt()).isEqualTo(Instant.parse("2026-08-15T20:00:00Z"));
              assertThat(forecast.validAt()).isEqualTo(Instant.parse("2026-08-15T21:00:00Z"));
              assertThat(forecast.temperatureC()).isEqualByComparingTo("23");
              assertThat(forecast.minTemperatureC()).isEqualByComparingTo("19");
              assertThat(forecast.maxTemperatureC()).isNull();
              assertThat(forecast.precipitationProbabilityPercent()).isEqualTo(30);
              assertThat(forecast.precipitationAmountMm()).isEqualByComparingTo("0.5");
              assertThat(forecast.precipitationType()).isEqualTo("1");
              assertThat(forecast.skyCode()).isEqualTo("3");
              assertThat(forecast.humidityPercent()).isEqualTo(80);
              assertThat(forecast.windSpeedMps()).isEqualByComparingTo("2.4");
            });
    assertThat(parsed.forecasts().get(1).maxTemperatureC()).isEqualByComparingTo("28");
  }

  @Test
  void mergesCompleteOrderedPagesWithoutLosingTimeGrid() {
    List<String> all = items();
    byte[] payload =
        combined(
            page(1, 2, all.subList(0, 8), all.size()),
            page(2, 2, all.subList(8, all.size()), all.size()),
            version());

    assertThat(parser.parse(KmaWeatherOperation.VILLAGE_FORECAST, payload).forecasts()).hasSize(2);
  }

  @Test
  void preservesOfficialOptionalCategoriesInRawPayloadWithoutRejectingNormalization() {
    List<String> values = items();
    values.add(item("UUU", "0600", "-1.2"));
    values.add(item("VVV", "0600", "2.1"));
    values.add(item("VEC", "0600", "360"));
    values.add(item("SNO", "0600", "적설없음"));
    values.add(item("WAV", "0600", "0"));

    assertThat(
            parser
                .parse(
                    KmaWeatherOperation.VILLAGE_FORECAST, combined(page(1, 1, values), version()))
                .forecasts())
        .hasSize(2);
  }

  @Test
  void rejectsProviderErrorMissingCategoryAndForecastSentinel() {
    assertInvalid(
        combined(
            page(1, 1, items()).replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\""),
            version()));

    List<String> missing = items();
    missing.removeIf(
        value -> value.contains("\"category\":\"POP\"") && value.contains("\"fcstTime\":\"0600\""));
    assertInvalid(combined(page(1, 1, missing), version()));

    List<String> sentinel = items();
    sentinel.set(0, item("TMP", "0600", "+900"));
    assertInvalid(combined(page(1, 1, sentinel), version()));
  }

  @Test
  void rejectsMissingPageInvalidVersionAndBrokenHourlyTimeGrid() {
    List<String> all = items();
    assertInvalid(combined(page(1, 2, all.subList(0, 8), all.size()), version()));
    assertInvalid(combined(page(1, 1, all), version().replace("202608160500", "invalid")));

    List<String> broken = new ArrayList<>(all);
    for (int index = 0; index < broken.size(); index++) {
      broken.set(
          index, broken.get(index).replace("\"fcstTime\":\"0700\"", "\"fcstTime\":\"0730\""));
    }
    assertInvalid(combined(page(1, 1, broken), version()));
  }

  private void assertInvalid(byte[] payload) {
    assertThatThrownBy(() -> parser.parse(KmaWeatherOperation.VILLAGE_FORECAST, payload))
        .isInstanceOf(KmaWeatherImportException.class);
  }

  private static List<String> items() {
    List<String> values = new ArrayList<>();
    values.add(item("TMP", "0600", "23"));
    values.add(item("TMN", "0600", "19"));
    values.add(item("POP", "0600", "30"));
    values.add(item("PCP", "0600", "1mm 미만"));
    values.add(item("PTY", "0600", "1"));
    values.add(item("SKY", "0600", "3"));
    values.add(item("REH", "0600", "80"));
    values.add(item("WSD", "0600", "2.4"));
    values.add(item("TMP", "0700", "24"));
    values.add(item("TMX", "0700", "28"));
    values.add(item("POP", "0700", "20"));
    values.add(item("PCP", "0700", "강수없음"));
    values.add(item("PTY", "0700", "0"));
    values.add(item("SKY", "0700", "1"));
    values.add(item("REH", "0700", "75"));
    values.add(item("WSD", "0700", "2.0"));
    return values;
  }

  private static String item(String category, String time, String value) {
    return "{\"baseDate\":\"20260816\",\"baseTime\":\"0500\",\"category\":\""
        + category
        + "\",\"fcstDate\":\"20260816\",\"fcstTime\":\""
        + time
        + "\",\"fcstValue\":\""
        + value
        + "\",\"nx\":52,\"ny\":38}";
  }

  private static String page(int pageNo, int totalPages, List<String> items) {
    return page(pageNo, totalPages, items, items.size());
  }

  private static String page(int pageNo, int totalPages, List<String> items, int totalCount) {
    int rows = (int) Math.ceil((double) totalCount / totalPages);
    return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL_SERVICE\"},"
        + "\"body\":{\"dataType\":\"JSON\",\"pageNo\":"
        + pageNo
        + ",\"numOfRows\":"
        + rows
        + ",\"totalCount\":"
        + totalCount
        + ",\"items\":{\"item\":["
        + String.join(",", items)
        + "]}}}}";
  }

  private static String version() {
    return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL_SERVICE\"},"
        + "\"body\":{\"dataType\":\"JSON\",\"pageNo\":1,\"numOfRows\":10,\"totalCount\":1,"
        + "\"items\":{\"item\":[{\"filetype\":\"SHRT\",\"version\":\"202608160500\"}]}}}}";
  }

  private static byte[] combined(String page, String version) {
    return combined(new String[] {page, version});
  }

  private static byte[] combined(String firstPage, String secondPage, String version) {
    return combined(new String[] {firstPage, secondPage, version});
  }

  private static byte[] combined(String[] values) {
    String version = values[values.length - 1];
    String pages = String.join(",", java.util.Arrays.copyOf(values, values.length - 1));
    return ("{\"forecastPages\":[" + pages + "],\"forecastVersion\":" + version + "}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String resource(String name) throws Exception {
    try (var input =
        KmaVillageForecastJsonParserTest.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException("fixture를 찾을 수 없습니다: " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
