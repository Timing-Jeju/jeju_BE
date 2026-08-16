package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopSource;
import com.timingjeju.api.application.tago.stop.TagoStopSourceResponse;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TagoStopClient implements TagoStopSource {
  static final int PAGE_SIZE = 100;
  private static final String SERVICE_PATH = "BusSttnInfoInqireService/";
  private final TagoStopHttpExecutor executor;

  @Autowired
  public TagoStopClient(ExternalApiExecutor executor) {
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

  TagoStopClient(TagoStopHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  public TagoStopSourceResponse fetchCityCodes() {
    return fetch(ExternalApiOperation.TAGO_CITY_CODE, SERVICE_PATH + "getCtyCodeList", null, 1);
  }

  public TagoStopSourceResponse fetchStations(String cityCode, int pageNo) {
    if (cityCode == null || cityCode.isBlank() || pageNo < 1) {
      throw TagoStopImportException.invalidRequest();
    }
    return fetch(
        ExternalApiOperation.TAGO_STATION_LIST, SERVICE_PATH + "getSttnNoList", cityCode, pageNo);
  }

  private TagoStopSourceResponse fetch(
      ExternalApiOperation operation, String path, String cityCode, int pageNo) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", cityCode == null ? "100" : Integer.toString(PAGE_SIZE));
    query.put("pageNo", Integer.toString(pageNo));
    query.put("_type", "json");
    if (cityCode != null) {
      query.put("cityCode", cityCode);
    }
    byte[] payload =
        executor.execute(
            new TagoStopHttpRequest(
                operation, path, Map.copyOf(query), ExternalApiResponseFormat.JSON));
    return new TagoStopSourceResponse(payload, SnapshotPayloadFormat.JSON);
  }
}
