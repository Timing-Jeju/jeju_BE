package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteSource;
import com.timingjeju.api.application.tago.route.TagoRouteSourceResponse;
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
public final class TagoRouteClient implements TagoRouteSource {
  private static final String SERVICE = "BusRouteInfoInqireService/";

  @Autowired
  public TagoRouteClient(ExternalApiExecutor executor) {
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

  TagoRouteClient(TagoRouteHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  private final TagoRouteHttpExecutor executor;

  @Override
  public TagoRouteSourceResponse fetchRouteList(String cityCode, String routeNo, int pageNo) {
    valid(cityCode, routeNo, pageNo);
    Map<String, String> query = paged(cityCode, pageNo);
    query.put("routeNo", routeNo);
    return fetch(ExternalApiOperation.TAGO_ROUTE_LIST, "getRouteNoList", query);
  }

  @Override
  public TagoRouteSourceResponse fetchRouteDetail(String cityCode, String routeId) {
    valid(cityCode, routeId, 1);
    Map<String, String> query = base(cityCode);
    query.put("routeId", routeId);
    return fetch(ExternalApiOperation.TAGO_ROUTE_DETAIL, "getRouteInfoIem", query);
  }

  @Override
  public TagoRouteSourceResponse fetchRouteStops(String cityCode, String routeId, int pageNo) {
    valid(cityCode, routeId, pageNo);
    Map<String, String> query = paged(cityCode, pageNo);
    query.put("routeId", routeId);
    return fetch(ExternalApiOperation.TAGO_ROUTE_STOPS, "getRouteAcctoThrghSttnList", query);
  }

  private TagoRouteSourceResponse fetch(
      ExternalApiOperation operation, String endpoint, Map<String, String> query) {
    byte[] payload =
        executor.execute(
            new TagoRouteHttpRequest(
                operation, SERVICE + endpoint, Map.copyOf(query), ExternalApiResponseFormat.JSON));
    return new TagoRouteSourceResponse(payload, SnapshotPayloadFormat.JSON);
  }

  private static Map<String, String> base(String city) {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("_type", "json");
    result.put("cityCode", city);
    return result;
  }

  private static Map<String, String> paged(String city, int page) {
    Map<String, String> result = base(city);
    result.put("pageNo", Integer.toString(page));
    result.put("numOfRows", "100");
    return result;
  }

  private static void valid(String city, String key, int page) {
    if (city == null || city.isBlank() || key == null || key.isBlank() || page < 1)
      throw TagoRouteImportException.invalidRequest();
  }
}
