package com.timingjeju.api.global.kma;

import com.timingjeju.api.application.kma.KmaWeatherOperation;
import com.timingjeju.api.application.kma.KmaWeatherSource;
import com.timingjeju.api.application.kma.KmaWeatherSourceResponse;
import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.domain.weather.ForecastBaseTime;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class KmaWeatherClient implements KmaWeatherSource {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
  private static final int PAGE_SIZE = 1000;
  private static final int MAX_VILLAGE_ITEMS = 5000;
  private final KmaWeatherHttpExecutor executor;
  private final ObjectMapper objectMapper;

  @Autowired
  public KmaWeatherClient(ExternalApiExecutor executor, ObjectMapper objectMapper) {
    this(
        request ->
            executor.execute(
                ExternalApiRequest.get(
                    request.operation(),
                    request.relativePath(),
                    request.queryParameters(),
                    request.format()),
                body -> body),
        objectMapper);
  }

  KmaWeatherClient(KmaWeatherHttpExecutor executor) {
    this(executor, new ObjectMapper());
  }

  KmaWeatherClient(KmaWeatherHttpExecutor executor, ObjectMapper objectMapper) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
  }

  @Override
  public KmaWeatherSourceResponse fetch(
      KmaWeatherOperation operation, ForecastBaseTime baseTime, int nx, int ny) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(baseTime, "baseTime은 필수입니다.");
    requireGrid(nx, ny);
    if (operation == KmaWeatherOperation.VILLAGE_FORECAST) {
      return fetchVillage(baseTime, nx, ny);
    }
    return new KmaWeatherSourceResponse(
        executor.execute(request(operation, baseTime, nx, ny, 1)), SnapshotPayloadFormat.JSON);
  }

  private KmaWeatherSourceResponse fetchVillage(ForecastBaseTime baseTime, int nx, int ny) {
    try {
      var root = objectMapper.createObjectNode();
      var pages = root.putArray("forecastPages");
      byte[] first =
          executor.execute(request(KmaWeatherOperation.VILLAGE_FORECAST, baseTime, nx, ny, 1));
      JsonNode firstPage = requireVillagePage(objectMapper.readTree(first), 1, null);
      pages.add(firstPage);
      int totalCount = exactInt(firstPage.path("response").path("body").path("totalCount"));
      int pageCount = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
      for (int page = 2; page <= pageCount; page++) {
        pages.add(
            requireVillagePage(
                objectMapper.readTree(
                    executor.execute(
                        request(KmaWeatherOperation.VILLAGE_FORECAST, baseTime, nx, ny, page))),
                page,
                totalCount));
      }
      root.set(
          "forecastVersion", objectMapper.readTree(executor.execute(versionRequest(baseTime))));
      return new KmaWeatherSourceResponse(
          objectMapper.writeValueAsBytes(root), SnapshotPayloadFormat.JSON);
    } catch (com.timingjeju.api.application.kma.KmaWeatherImportException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw com.timingjeju.api.application.kma.KmaWeatherImportException.invalidResponse();
    }
  }

  private static JsonNode requireVillagePage(
      JsonNode page, int expectedPage, Integer expectedTotal) {
    JsonNode response = page.path("response");
    JsonNode header = response.path("header");
    JsonNode body = response.path("body");
    int pageNo = exactInt(body.path("pageNo"));
    int pageSize = exactInt(body.path("numOfRows"));
    int totalCount = exactInt(body.path("totalCount"));
    if (!page.isObject()
        || !response.isObject()
        || !header.isObject()
        || !body.isObject()
        || !header.path("resultCode").isTextual()
        || !"00".equals(header.path("resultCode").asString())
        || pageNo != expectedPage
        || pageSize != PAGE_SIZE
        || totalCount < 1
        || totalCount > MAX_VILLAGE_ITEMS
        || (expectedTotal != null && totalCount != expectedTotal)) {
      throw com.timingjeju.api.application.kma.KmaWeatherImportException.invalidResponse();
    }
    return page;
  }

  private static int exactInt(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw com.timingjeju.api.application.kma.KmaWeatherImportException.invalidResponse();
    }
    return node.asInt();
  }

  private static KmaWeatherHttpRequest request(
      KmaWeatherOperation operation, ForecastBaseTime baseTime, int nx, int ny, int pageNo) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageNo", Integer.toString(pageNo));
    query.put("numOfRows", Integer.toString(PAGE_SIZE));
    query.put("dataType", "JSON");
    query.put("base_date", DATE.format(baseTime.baseDate()));
    query.put("base_time", TIME.format(baseTime.baseTime()));
    query.put("nx", Integer.toString(nx));
    query.put("ny", Integer.toString(ny));
    return new KmaWeatherHttpRequest(
        externalOperation(operation),
        operation.providerOperation(),
        query,
        ExternalApiResponseFormat.JSON);
  }

  private static KmaWeatherHttpRequest versionRequest(ForecastBaseTime baseTime) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageNo", "1");
    query.put("numOfRows", "10");
    query.put("dataType", "JSON");
    query.put("ftype", "SHRT");
    query.put("basedatetime", DATE.format(baseTime.baseDate()) + TIME.format(baseTime.baseTime()));
    return new KmaWeatherHttpRequest(
        ExternalApiOperation.KMA_FORECAST_VERSION,
        "getFcstVersion",
        query,
        ExternalApiResponseFormat.JSON);
  }

  private static ExternalApiOperation externalOperation(KmaWeatherOperation operation) {
    return switch (operation) {
      case ULTRA_CURRENT -> ExternalApiOperation.KMA_ULTRA_CURRENT;
      case ULTRA_FORECAST -> ExternalApiOperation.KMA_ULTRA_FORECAST;
      case VILLAGE_FORECAST -> ExternalApiOperation.KMA_VILLAGE_FORECAST;
    };
  }

  private static void requireGrid(int nx, int ny) {
    if (nx < 1 || nx > 149 || ny < 1 || ny > 253) {
      throw new IllegalArgumentException("KMA DFS 격자 범위가 올바르지 않습니다.");
    }
  }
}
