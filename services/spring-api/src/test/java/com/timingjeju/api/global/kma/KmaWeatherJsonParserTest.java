package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.kma.KmaWeatherImportError;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class KmaWeatherJsonParserTest {

  private final KmaWeatherJsonParser parser = new KmaWeatherJsonParser(new ObjectMapper());

  @Test
  void parsesOfficialUltraCurrentEnvelopeAndAggregatesExactCategories() {
    var parsed =
        parser.parse(
            KmaWeatherOperation.ULTRA_CURRENT,
            envelope(
                current("T1H", "25.4"),
                current("RN1", "1mm 미만"),
                current("PTY", "1"),
                current("REH", "82"),
                current("WSD", "3.7"),
                current("VEC", "360"),
                current("UUU", "-1.2"),
                current("VVV", "3.5")));

    assertThat(parsed.rawItemCount()).isEqualTo(8);
    assertThat(parsed.forecasts()).isEmpty();
    assertThat(parsed.observations())
        .singleElement()
        .satisfies(
            observation -> {
              assertThat(observation.baseDate()).isEqualTo(LocalDate.of(2026, 8, 16));
              assertThat(observation.baseTime()).isEqualTo(LocalTime.of(0, 0));
              assertThat(observation.observedAt()).isEqualTo(Instant.parse("2026-08-15T15:00:00Z"));
              assertThat(observation.temperatureC()).isEqualByComparingTo("25.4");
              assertThat(observation.precipitationMm()).isEqualByComparingTo("0.5");
              assertThat(observation.precipitationType()).isEqualTo("1");
              assertThat(observation.humidityPercent()).isEqualTo(82);
              assertThat(observation.windSpeedMps()).isEqualByComparingTo("3.7");
              assertThat(observation.windDirectionDeg()).isEqualTo(360);
            });
  }

  @Test
  void parsesOfficialUltraForecastEnvelopeAndAggregatesEachForecastTime() {
    List<String> items = new ArrayList<>();
    for (String valid : List.of("0100", "0200")) {
      items.add(forecast("T1H", valid, "24.0"));
      items.add(forecast("RN1", valid, "강수없음"));
      items.add(forecast("PTY", valid, "0"));
      items.add(forecast("SKY", valid, "3"));
      items.add(forecast("REH", valid, "75"));
      items.add(forecast("WSD", valid, "2.5"));
      items.add(forecast("VEC", valid, "0"));
      items.add(forecast("LGT", valid, "0"));
    }

    var parsed = parser.parse(KmaWeatherOperation.ULTRA_FORECAST, envelope(items));

    assertThat(parsed.observations()).isEmpty();
    assertThat(parsed.forecasts())
        .hasSize(2)
        .allSatisfy(
            forecast -> {
              assertThat(forecast.forecastedAt()).isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
              assertThat(forecast.forecastType()).isEqualTo("ultra_short");
              assertThat(forecast.precipitationAmountMm()).isEqualByComparingTo(BigDecimal.ZERO);
              assertThat(forecast.skyCode()).isEqualTo("3");
            });
    assertThat(parsed.forecasts().getFirst().validAt())
        .isEqualTo(Instant.parse("2026-08-15T16:00:00Z"));
  }

  @ParameterizedTest
  @CsvSource({"강수없음,0", "1mm 미만,0.5", "0.7mm,0.7", "30.0~50.0mm,30.0", "50.0mm 이상,50.0"})
  void parsesOfficialRainfallStrings(String source, String expected) {
    var parsed = parser.parse(KmaWeatherOperation.ULTRA_CURRENT, currentEnvelope("RN1", source));

    assertThat(parsed.observations().getFirst().precipitationMm()).isEqualByComparingTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"0,0", "360,360"})
  void acceptsWindDirectionBoundaries(String source, int expected) {
    var parsed = parser.parse(KmaWeatherOperation.ULTRA_CURRENT, currentEnvelope("VEC", source));

    assertThat(parsed.observations().getFirst().windDirectionDeg()).isEqualTo(expected);
  }

  @Test
  void crossesKoreaStandardTimeDateBoundaryWithoutUsingUtcDate() {
    var parsed = parser.parse(KmaWeatherOperation.ULTRA_FORECAST, forecastEnvelope());

    assertThat(parsed.forecasts().getFirst().forecastedAt())
        .isEqualTo(Instant.parse("2026-08-15T15:30:00Z"));
    assertThat(parsed.forecasts().getFirst().validAt())
        .isEqualTo(Instant.parse("2026-08-15T16:00:00Z"));
  }

  @Test
  void rejectsMissingAndDuplicateRequiredCategories() {
    byte[] missing =
        envelope(
            current("T1H", "25"),
            current("RN1", "0"),
            current("PTY", "0"),
            current("REH", "70"),
            current("WSD", "2"));
    byte[] duplicate =
        envelope(
            current("T1H", "25"),
            current("RN1", "0"),
            current("PTY", "0"),
            current("REH", "70"),
            current("WSD", "2"),
            current("VEC", "0"),
            current("VEC", "360"));

    assertInvalid(missing);
    assertInvalid(duplicate);
  }

  @Test
  void rejectsWrongJsonTypesAndDuplicateJsonFields() {
    String wrongType =
        new String(currentEnvelope("REH", "70"), StandardCharsets.UTF_8)
            .replace("\"obsrValue\":\"70\"", "\"obsrValue\":70");
    String duplicateField =
        new String(currentEnvelope("REH", "70"), StandardCharsets.UTF_8)
            .replace("\"category\":\"T1H\"", "\"category\":\"T1H\",\"category\":\"REH\"");

    assertInvalid(wrongType.getBytes(StandardCharsets.UTF_8));
    assertInvalid(duplicateField.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @CsvSource({"T1H,101", "RN1,-0.1", "PTY,4", "REH,101", "WSD,-0.1", "VEC,361"})
  void rejectsValuesOutsideCurrentRanges(String category, String value) {
    assertInvalid(currentEnvelope(category, value));
  }

  @ParameterizedTest
  @CsvSource({"TMP,25", "PCP,1mm 미만", "TMN,18", "TMX,30"})
  void rejectsVillageForecastCategoriesInsteadOfMisclassifyingThem(String category, String value) {
    List<String> items = forecastItems("0100");
    items.add(forecast(category, "0100", value));

    assertThatThrownBy(() -> parser.parse(KmaWeatherOperation.ULTRA_FORECAST, envelope(items)))
        .isInstanceOf(KmaWeatherImportException.class)
        .extracting(failure -> ((KmaWeatherImportException) failure).code())
        .isEqualTo(KmaWeatherImportError.UNSUPPORTED_CATEGORY);
  }

  @Test
  void ignoresNewOfficialUltraForecastPopWithoutPersistingItAsVillageData() {
    List<String> items = forecastItems("0100");
    items.add(forecast("POP", "0100", "20"));

    var parsed = parser.parse(KmaWeatherOperation.ULTRA_FORECAST, envelope(items));

    assertThat(parsed.forecasts())
        .singleElement()
        .satisfies(
            forecast -> {
              assertThat(forecast.precipitationAmountMm()).isEqualByComparingTo("0");
              assertThat(forecast.precipitationType()).isEqualTo("0");
            });
  }

  @Test
  void rejectsNonSuccessHeaderAndForecastOutsideSixHourWindow() {
    String error =
        new String(forecastEnvelope(), StandardCharsets.UTF_8)
            .replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"");
    List<String> tooLate = forecastItems("0700");

    assertInvalid(error.getBytes(StandardCharsets.UTF_8));
    assertForecastInvalid(envelope(tooLate));
  }

  private static byte[] currentEnvelope(String replacedCategory, String replacement) {
    List<String> items =
        new ArrayList<>(
            List.of(
                current("T1H", "25"),
                current("RN1", "0"),
                current("PTY", "0"),
                current("REH", "70"),
                current("WSD", "2"),
                current("VEC", "180")));
    for (int index = 0; index < items.size(); index++) {
      if (items.get(index).contains("\"category\":\"" + replacedCategory + "\"")) {
        items.set(index, current(replacedCategory, replacement));
      }
    }
    return envelope(items);
  }

  private static byte[] forecastEnvelope() {
    return envelope(forecastItems("0100"));
  }

  private static List<String> forecastItems(String validTime) {
    return new ArrayList<>(
        List.of(
            forecast("T1H", validTime, "24"),
            forecast("RN1", validTime, "0"),
            forecast("PTY", validTime, "0"),
            forecast("SKY", validTime, "1"),
            forecast("REH", validTime, "70"),
            forecast("WSD", validTime, "2")));
  }

  private static String current(String category, String value) {
    return "{\"baseDate\":\"20260816\",\"baseTime\":\"0000\",\"category\":\""
        + category
        + "\",\"nx\":52,\"ny\":38,\"obsrValue\":\""
        + value
        + "\"}";
  }

  private static String forecast(String category, String validTime, String value) {
    return "{\"baseDate\":\"20260816\",\"baseTime\":\"0030\",\"category\":\""
        + category
        + "\",\"fcstDate\":\"20260816\",\"fcstTime\":\""
        + validTime
        + "\",\"fcstValue\":\""
        + value
        + "\",\"nx\":52,\"ny\":38}";
  }

  private static byte[] envelope(String... items) {
    return envelope(List.of(items));
  }

  private static byte[] envelope(List<String> items) {
    String json =
        "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL_SERVICE\"},"
            + "\"body\":{\"dataType\":\"JSON\",\"pageNo\":1,\"numOfRows\":1000,\"totalCount\":"
            + items.size()
            + ",\"items\":{\"item\":["
            + String.join(",", items)
            + "]}}}}";
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private void assertInvalid(byte[] payload) {
    assertThatThrownBy(() -> parser.parse(KmaWeatherOperation.ULTRA_CURRENT, payload))
        .isInstanceOf(KmaWeatherImportException.class);
  }

  private void assertForecastInvalid(byte[] payload) {
    assertThatThrownBy(() -> parser.parse(KmaWeatherOperation.ULTRA_FORECAST, payload))
        .isInstanceOf(KmaWeatherImportException.class);
  }
}
