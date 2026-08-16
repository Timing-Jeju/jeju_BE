package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherBatch;
import com.timingjeju.api.application.kma.KmaWeatherForecast;
import com.timingjeju.api.application.kma.KmaWeatherImportException;
import com.timingjeju.api.application.kma.KmaWeatherObservation;
import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.application.kma.KmaWeatherParser;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class KmaWeatherJsonParser implements KmaWeatherParser {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("HHmm")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final Set<String> CURRENT_REQUIRED =
      Set.of("T1H", "RN1", "PTY", "REH", "WSD", "VEC");
  private static final Set<String> CURRENT_OPTIONAL = Set.of("UUU", "VVV");
  private static final Set<String> FORECAST_REQUIRED =
      Set.of("T1H", "RN1", "PTY", "SKY", "REH", "WSD");
  private static final Set<String> FORECAST_OPTIONAL = Set.of("UUU", "VVV", "VEC", "LGT", "POP");
  private static final Set<String> VILLAGE_ONLY = Set.of("TMP", "PCP", "TMN", "TMX");
  private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");
  private static final Pattern MILLIMETERS =
      Pattern.compile("([+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*mm", Pattern.CASE_INSENSITIVE);
  private static final Pattern RANGE_MILLIMETERS =
      Pattern.compile(
          "([+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*[~-]\\s*([+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*mm",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ABOVE_MILLIMETERS =
      Pattern.compile("([+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*mm\\s*이상", Pattern.CASE_INSENSITIVE);

  private final ObjectReader reader;

  public KmaWeatherJsonParser(ObjectMapper objectMapper) {
    reader =
        Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.")
            .reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @Override
  public KmaWeatherBatch parse(KmaWeatherOperation operation, byte[] payload) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(payload, "payload는 필수입니다.");
    try {
      List<Item> items = readItems(payload);
      return switch (operation) {
        case ULTRA_CURRENT -> current(items);
        case ULTRA_FORECAST -> forecast(items);
      };
    } catch (KmaWeatherImportException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private List<Item> readItems(byte[] payload) {
    try {
      JsonNode response = reader.readTree(payload).path("response");
      JsonNode header = response.path("header");
      JsonNode body = response.path("body");
      if (!response.isObject()
          || !header.isObject()
          || !body.isObject()
          || !"00".equals(requiredText(header, "resultCode"))
          || !"JSON".equals(requiredText(body, "dataType"))
          || exactInt(body.path("pageNo")) != 1
          || exactInt(body.path("numOfRows")) != 1000) {
        throw KmaWeatherImportException.invalidResponse();
      }
      JsonNode itemNodes = body.path("items").path("item");
      int totalCount = exactInt(body.path("totalCount"));
      if (!itemNodes.isArray()
          || totalCount < 1
          || totalCount > 1000
          || totalCount != itemNodes.size()) {
        throw KmaWeatherImportException.invalidResponse();
      }
      List<Item> items = new ArrayList<>(totalCount);
      for (JsonNode node : itemNodes) {
        if (!node.isObject()) throw KmaWeatherImportException.invalidResponse();
        items.add(
            new Item(
                date(requiredText(node, "baseDate")),
                time(requiredText(node, "baseTime")),
                requiredText(node, "category"),
                optionalText(node, "obsrValue"),
                optionalDate(node, "fcstDate"),
                optionalTime(node, "fcstTime"),
                optionalText(node, "fcstValue"),
                exactInt(node.path("nx")),
                exactInt(node.path("ny"))));
      }
      return items;
    } catch (KmaWeatherImportException failure) {
      throw failure;
    } catch (Exception failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static KmaWeatherBatch current(List<Item> items) {
    Item first = items.getFirst();
    requireCurrentBase(first);
    Map<String, String> values = new LinkedHashMap<>();
    for (Item item : items) {
      requireSameBaseAndGrid(first, item);
      if (item.observationValue() == null
          || item.forecastDate() != null
          || item.forecastTime() != null
          || item.forecastValue() != null) {
        throw KmaWeatherImportException.invalidResponse();
      }
      requireAllowed(item.category(), CURRENT_REQUIRED, CURRENT_OPTIONAL);
      if (values.putIfAbsent(item.category(), item.observationValue()) != null) {
        throw KmaWeatherImportException.invalidResponse();
      }
    }
    requireExactRequired(values.keySet(), CURRENT_REQUIRED);
    Instant observedAt = instant(first.baseDate(), first.baseTime());
    KmaWeatherObservation observation =
        new KmaWeatherObservation(
            observedAt,
            first.baseDate(),
            first.baseTime(),
            decimal(values.get("T1H"), new BigDecimal("-100"), new BigDecimal("100")),
            rainfall(values.get("RN1")),
            precipitationType(values.get("PTY")),
            integer(values.get("REH"), 0, 100),
            decimal(values.get("WSD"), BigDecimal.ZERO, new BigDecimal("200")),
            integer(values.get("VEC"), 0, 360));
    return new KmaWeatherBatch(
        first.nx(), first.ny(), items.size(), observedAt, List.of(observation), List.of());
  }

  private static KmaWeatherBatch forecast(List<Item> items) {
    Item first = items.getFirst();
    requireForecastBase(first);
    Map<ValidTime, Map<String, String>> groups = new LinkedHashMap<>();
    for (Item item : items) {
      requireSameBaseAndGrid(first, item);
      if (item.observationValue() != null
          || item.forecastDate() == null
          || item.forecastTime() == null
          || item.forecastValue() == null) {
        throw KmaWeatherImportException.invalidResponse();
      }
      requireAllowed(item.category(), FORECAST_REQUIRED, FORECAST_OPTIONAL);
      Map<String, String> values =
          groups.computeIfAbsent(
              new ValidTime(item.forecastDate(), item.forecastTime()),
              ignored -> new LinkedHashMap<>());
      if (values.putIfAbsent(item.category(), item.forecastValue()) != null) {
        throw KmaWeatherImportException.invalidResponse();
      }
    }
    Instant forecastedAt = instant(first.baseDate(), first.baseTime());
    List<KmaWeatherForecast> forecasts = new ArrayList<>();
    for (Map.Entry<ValidTime, Map<String, String>> entry : groups.entrySet()) {
      requireExactRequired(entry.getValue().keySet(), FORECAST_REQUIRED);
      Instant validAt = instant(entry.getKey().date(), entry.getKey().time());
      if (validAt.isBefore(forecastedAt)
          || validAt.isAfter(forecastedAt.plus(java.time.Duration.ofHours(6)))) {
        throw KmaWeatherImportException.invalidResponse();
      }
      Map<String, String> values = entry.getValue();
      forecasts.add(
          new KmaWeatherForecast(
              forecastedAt,
              validAt,
              "ultra_short",
              Integer.toString(integer(values.get("SKY"), 1, 4)),
              precipitationType(values.get("PTY")),
              rainfall(values.get("RN1")),
              decimal(values.get("T1H"), new BigDecimal("-100"), new BigDecimal("100")),
              integer(values.get("REH"), 0, 100),
              decimal(values.get("WSD"), BigDecimal.ZERO, new BigDecimal("200"))));
    }
    forecasts.sort(java.util.Comparator.comparing(KmaWeatherForecast::validAt));
    Instant watermark =
        forecasts.stream().map(KmaWeatherForecast::validAt).max(Instant::compareTo).orElseThrow();
    return new KmaWeatherBatch(
        first.nx(), first.ny(), items.size(), watermark, List.of(), forecasts);
  }

  private static void requireCurrentBase(Item item) {
    if (item.baseTime().getMinute() != 0 || item.baseTime().getSecond() != 0) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static void requireForecastBase(Item item) {
    if (item.baseTime().getMinute() != 30 || item.baseTime().getSecond() != 0) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static void requireSameBaseAndGrid(Item expected, Item actual) {
    if (!expected.baseDate().equals(actual.baseDate())
        || !expected.baseTime().equals(actual.baseTime())
        || expected.nx() != actual.nx()
        || expected.ny() != actual.ny()
        || actual.nx() < 1
        || actual.nx() > 149
        || actual.ny() < 1
        || actual.ny() > 253) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static void requireAllowed(String category, Set<String> required, Set<String> optional) {
    if (VILLAGE_ONLY.contains(category)) {
      throw KmaWeatherImportException.unsupportedCategory();
    }
    if (!required.contains(category) && !optional.contains(category)) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static void requireExactRequired(Set<String> actual, Set<String> required) {
    if (!actual.containsAll(required)) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static BigDecimal rainfall(String raw) {
    String value = requiredValue(raw);
    if ("강수없음".equals(value)) return BigDecimal.ZERO;
    if ("1mm미만".equalsIgnoreCase(value.replace(" ", ""))) return new BigDecimal("0.5");
    Matcher range = RANGE_MILLIMETERS.matcher(value);
    if (range.matches()) return nonNegative(new BigDecimal(range.group(1)));
    Matcher above = ABOVE_MILLIMETERS.matcher(value);
    if (above.matches()) return nonNegative(new BigDecimal(above.group(1)));
    Matcher millimeters = MILLIMETERS.matcher(value);
    if (millimeters.matches()) return nonNegative(new BigDecimal(millimeters.group(1)));
    return nonNegative(decimal(value, BigDecimal.ZERO, new BigDecimal("9999")));
  }

  private static BigDecimal nonNegative(BigDecimal value) {
    if (value.signum() < 0) throw KmaWeatherImportException.invalidResponse();
    return value;
  }

  private static String precipitationType(String value) {
    int code = integer(value, 0, 7);
    if (!Set.of(0, 1, 2, 3, 5, 6, 7).contains(code)) {
      throw KmaWeatherImportException.invalidResponse();
    }
    return Integer.toString(code);
  }

  private static int integer(String raw, int minimum, int maximum) {
    String value = requiredValue(raw);
    if (!value.matches("[+-]?\\d+")) throw KmaWeatherImportException.invalidResponse();
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < minimum || parsed > maximum) throw KmaWeatherImportException.invalidResponse();
      return parsed;
    } catch (NumberFormatException failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static BigDecimal decimal(String raw, BigDecimal minimum, BigDecimal maximum) {
    String value = requiredValue(raw);
    if (!DECIMAL.matcher(value).matches()) throw KmaWeatherImportException.invalidResponse();
    try {
      BigDecimal parsed = new BigDecimal(value);
      if (parsed.compareTo(minimum) < 0 || parsed.compareTo(maximum) > 0) {
        throw KmaWeatherImportException.invalidResponse();
      }
      return parsed;
    } catch (NumberFormatException failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static String requiredValue(String value) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw KmaWeatherImportException.invalidResponse();
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual()) throw KmaWeatherImportException.invalidResponse();
    String text = value.asString();
    if (text.isBlank() || !text.equals(text.strip()) || text.length() > 128) {
      throw KmaWeatherImportException.invalidResponse();
    }
    return text;
  }

  private static String optionalText(JsonNode node, String field) {
    return node.has(field) ? requiredText(node, field) : null;
  }

  private static LocalDate optionalDate(JsonNode node, String field) {
    return node.has(field) ? date(requiredText(node, field)) : null;
  }

  private static LocalTime optionalTime(JsonNode node, String field) {
    return node.has(field) ? time(requiredText(node, field)) : null;
  }

  private static LocalDate date(String value) {
    try {
      return LocalDate.parse(value, DATE);
    } catch (DateTimeException failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static LocalTime time(String value) {
    if (!value.matches("\\d{4}")) throw KmaWeatherImportException.invalidResponse();
    try {
      int hour = Integer.parseInt(value.substring(0, 2));
      int minute = Integer.parseInt(value.substring(2));
      return LocalTime.of(hour, minute);
    } catch (DateTimeException | NumberFormatException failure) {
      throw KmaWeatherImportException.invalidResponse();
    }
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw KmaWeatherImportException.invalidResponse();
    }
    return node.asInt();
  }

  private static Instant instant(LocalDate date, LocalTime time) {
    return LocalDateTime.of(date, time).atZone(KOREA).toInstant();
  }

  private record ValidTime(LocalDate date, LocalTime time) {}

  private record Item(
      LocalDate baseDate,
      LocalTime baseTime,
      String category,
      String observationValue,
      LocalDate forecastDate,
      LocalTime forecastTime,
      String forecastValue,
      int nx,
      int ny) {}
}
