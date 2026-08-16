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

@Component
public final class KmaWeatherClient implements KmaWeatherSource {

  private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
  private static final int PAGE_SIZE = 1000;
  private final KmaWeatherHttpExecutor executor;

  @Autowired
  public KmaWeatherClient(ExternalApiExecutor executor) {
    this(
        request ->
            executor.execute(
                ExternalApiRequest.get(
                    request.operation(),
                    request.relativePath(),
                    request.queryParameters(),
                    request.format()),
                body -> body));
  }

  KmaWeatherClient(KmaWeatherHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public KmaWeatherSourceResponse fetch(
      KmaWeatherOperation operation, ForecastBaseTime baseTime, int nx, int ny) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    Objects.requireNonNull(baseTime, "baseTime은 필수입니다.");
    requireGrid(nx, ny);
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageNo", "1");
    query.put("numOfRows", Integer.toString(PAGE_SIZE));
    query.put("dataType", "JSON");
    query.put("base_date", DATE.format(baseTime.baseDate()));
    query.put("base_time", TIME.format(baseTime.baseTime()));
    query.put("nx", Integer.toString(nx));
    query.put("ny", Integer.toString(ny));
    KmaWeatherHttpRequest request =
        new KmaWeatherHttpRequest(
            externalOperation(operation),
            operation.providerOperation(),
            query,
            ExternalApiResponseFormat.JSON);
    return new KmaWeatherSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }

  private static ExternalApiOperation externalOperation(KmaWeatherOperation operation) {
    return switch (operation) {
      case ULTRA_CURRENT -> ExternalApiOperation.KMA_ULTRA_CURRENT;
      case ULTRA_FORECAST -> ExternalApiOperation.KMA_ULTRA_FORECAST;
    };
  }

  private static void requireGrid(int nx, int ny) {
    if (nx < 1 || nx > 149 || ny < 1 || ny > 253) {
      throw new IllegalArgumentException("KMA DFS 격자 범위가 올바르지 않습니다.");
    }
  }
}
