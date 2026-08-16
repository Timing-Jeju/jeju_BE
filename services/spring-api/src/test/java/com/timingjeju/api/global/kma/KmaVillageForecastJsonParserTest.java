package com.timingjeju.api.global.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.application.kma.KmaWeatherResponsePart;
import com.timingjeju.api.application.kma.KmaWeatherSourceResponse;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class KmaVillageForecastJsonParserTest {
  private static final LocalDate BASE_DATE = LocalDate.of(2026, 8, 16);
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
  private final KmaWeatherJsonParser parser = new KmaWeatherJsonParser(new ObjectMapper());

  @ParameterizedTest(name = "official full horizon for base {0}")
  @CsvSource({"02:00", "05:00", "08:00", "11:00", "14:00", "17:00", "20:00", "23:00"})
  void parsesEveryOfficial20241128FullHorizon(LocalTime baseTime) {
    List<KmaVillageForecastSchedule.Slot> expected =
        KmaVillageForecastSchedule.slots(BASE_DATE, baseTime);

    var parsed = parser.parse(KmaWeatherOperation.VILLAGE_FORECAST, validPayload(baseTime));

    assertThat(parsed.forecasts()).hasSize(expected.size());
    assertThat(parsed.forecasts().getFirst().forecastVersion()).isEqualTo(version(baseTime));
    assertThat(parsed.forecasts().getLast().validAt())
        .isEqualTo(
            expected.getLast().validTime().atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant());
  }

  @Test
  void keepsExtendedPcpAndWsdCodesSemanticAndNeverStoresThemAsMmOrMps() {
    var parsed =
        parser.parse(KmaWeatherOperation.VILLAGE_FORECAST, validPayload(LocalTime.of(5, 0)));

    assertThat(
            parsed.forecasts().stream().filter(value -> value.precipitationIntensityCode() != null))
        .hasSize(8)
        .allSatisfy(
            value -> {
              assertThat(value.precipitationIntensityCode()).isBetween(1, 3);
              assertThat(value.windStrengthCode()).isBetween(1, 3);
              assertThat(value.precipitationAmountMm()).isNull();
              assertThat(value.windSpeedMps()).isNull();
            });
    assertThat(
            parsed.forecasts().stream().filter(value -> value.precipitationIntensityCode() == null))
        .allSatisfy(
            value -> {
              assertThat(value.precipitationAmountMm()).isNotNull();
              assertThat(value.windSpeedMps()).isNotNull();
            });
  }

  @Test
  void rejectsMissingBoundaryDuplicateCategoryAndIllegalInterval() {
    LocalTime base = LocalTime.of(5, 0);
    List<String> complete = items(base);
    List<String> missingLastBoundary = new ArrayList<>(complete);
    LocalDateTime last = KmaVillageForecastSchedule.slots(BASE_DATE, base).getLast().validTime();
    missingLastBoundary.removeIf(value -> value.contains(validMarker(last)));
    List<String> duplicate = new ArrayList<>(complete);
    duplicate.add(complete.getFirst());
    List<String> illegalInterval = new ArrayList<>(complete);
    LocalDateTime first = KmaVillageForecastSchedule.slots(BASE_DATE, base).getFirst().validTime();
    for (int index = 0; index < illegalInterval.size(); index++) {
      if (illegalInterval.get(index).contains(validMarker(first))) {
        illegalInterval.set(index, illegalInterval.get(index).replace(TIME.format(first), "0630"));
      }
    }

    assertInvalid(payload(base, missingLastBoundary));
    assertInvalid(payload(base, duplicate));
    assertInvalid(payload(base, illegalInterval));
  }

  @Test
  void rejectsCapturedPageTwoAndVersionProviderErrorsWithoutPartialNormalization() {
    LocalTime base = LocalTime.of(5, 0);
    List<String> complete = items(base);
    int split = complete.size() / 2;
    String pageOne = page(1, complete.subList(0, split), complete.size(), 1000);
    String pageTwoError =
        page(2, complete.subList(split, complete.size()), complete.size(), 1000)
            .replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"");
    String versionError =
        versionEnvelope(base).replace("\"resultCode\":\"00\"", "\"resultCode\":\"03\"");

    assertInvalid(framed(pageOne, pageTwoError));
    assertInvalid(framed(page(1, complete, complete.size(), 1000), versionError));
  }

  private void assertInvalid(byte[] payload) {
    assertThatThrownBy(() -> parser.parse(KmaWeatherOperation.VILLAGE_FORECAST, payload))
        .isInstanceOf(KmaWeatherImportException.class);
  }

  private static byte[] validPayload(LocalTime base) {
    return payload(base, items(base));
  }

  private static byte[] payload(LocalTime base, List<String> items) {
    return framed(page(1, items, items.size(), 1000), versionEnvelope(base));
  }

  private static List<String> items(LocalTime base) {
    List<String> values = new ArrayList<>();
    List<KmaVillageForecastSchedule.Slot> slots = KmaVillageForecastSchedule.slots(BASE_DATE, base);
    for (int index = 0; index < slots.size(); index++) {
      var slot = slots.get(index);
      String pcp = slot.qualitative() ? Integer.toString(index % 3 + 1) : "강수없음";
      String wsd = slot.qualitative() ? Integer.toString(index % 3 + 1) : "2.4";
      values.add(item(base, slot.validTime(), "TMP", "23"));
      values.add(item(base, slot.validTime(), "POP", "30"));
      values.add(item(base, slot.validTime(), "PCP", pcp));
      values.add(item(base, slot.validTime(), "PTY", "1"));
      values.add(item(base, slot.validTime(), "SKY", "3"));
      values.add(item(base, slot.validTime(), "REH", "80"));
      values.add(item(base, slot.validTime(), "WSD", wsd));
      if (slot.qualitative())
        values.add(item(base, slot.validTime(), "SNO", Integer.toString(index % 2 + 1)));
      if (index == 0) values.add(item(base, slot.validTime(), "TMN", "19"));
      if (index == 1) values.add(item(base, slot.validTime(), "TMX", "28"));
    }
    return values;
  }

  private static String item(LocalTime base, LocalDateTime valid, String category, String value) {
    return "{\"baseDate\":\""
        + DATE.format(BASE_DATE)
        + "\",\"baseTime\":\""
        + TIME.format(base)
        + "\",\"category\":\""
        + category
        + "\",\"fcstDate\":\""
        + DATE.format(valid.toLocalDate())
        + "\",\"fcstTime\":\""
        + TIME.format(valid.toLocalTime())
        + "\",\"fcstValue\":\""
        + value
        + "\",\"nx\":52,\"ny\":38}";
  }

  private static String validMarker(LocalDateTime valid) {
    return "\"fcstDate\":\""
        + DATE.format(valid.toLocalDate())
        + "\",\"fcstTime\":\""
        + TIME.format(valid.toLocalTime())
        + "\"";
  }

  private static String page(int pageNo, List<String> items, int totalCount, int rows) {
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

  private static String versionEnvelope(LocalTime base) {
    return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"dataType\":\"JSON\","
        + "\"pageNo\":1,\"numOfRows\":10,\"totalCount\":1,\"items\":{\"item\":[{\"filetype\":\"SHRT\","
        + "\"version\":\""
        + version(base)
        + "\"}]}}}}";
  }

  private static String version(LocalTime base) {
    return DATE.format(BASE_DATE) + TIME.format(base);
  }

  private static byte[] framed(String... responses) {
    List<KmaWeatherResponsePart> parts = new ArrayList<>();
    for (int index = 0; index < responses.length; index++) {
      boolean version =
          index == responses.length - 1 && responses[index].contains("\"filetype\":\"SHRT\"");
      parts.add(
          new KmaWeatherResponsePart(
              version ? "getFcstVersion" : "getVilageFcst",
              version ? 1 : index + 1,
              responses[index].getBytes(StandardCharsets.UTF_8),
              SnapshotPayloadFormat.JSON));
    }
    return new KmaWeatherSourceResponse(parts).payload();
  }
}
