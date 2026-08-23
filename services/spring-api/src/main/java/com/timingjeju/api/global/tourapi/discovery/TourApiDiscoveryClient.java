package com.timingjeju.api.global.tourapi.discovery;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryImportCommand;
import com.timingjeju.api.application.tourapi.discovery.DiscoveryOperation;
import com.timingjeju.api.application.tourapi.discovery.DiscoverySource;
import com.timingjeju.api.application.tourapi.place.PlaceListRequestContract;
import com.timingjeju.api.application.tourapi.place.PlaceListSourceResponse;
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
public final class TourApiDiscoveryClient implements DiscoverySource {

  private final DiscoveryHttpExecutor executor;

  @Autowired
  public TourApiDiscoveryClient(ExternalApiExecutor executor) {
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

  TourApiDiscoveryClient(DiscoveryHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public PlaceListSourceResponse fetch(DiscoveryImportCommand command, int pageNo) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    if (pageNo < 1 || pageNo > command.pageBudget()) {
      throw new IllegalArgumentException("pageNo가 command quota 범위를 벗어났습니다.");
    }
    Map<String, String> query = commonQuery(pageNo);
    switch (command.operation()) {
      case LOCATION -> {
        query.put("mapX", Double.toString(command.longitude()));
        query.put("mapY", Double.toString(command.latitude()));
        query.put("radius", Integer.toString(command.radiusMeters()));
      }
      case KEYWORD -> {
        query.put("keyword", command.keyword());
        query.put("lDongRegnCd", command.legalRegionCode());
      }
      case STAY -> query.put("lDongRegnCd", command.legalRegionCode());
    }
    DiscoveryOperation operation = command.operation();
    DiscoveryHttpRequest request =
        new DiscoveryHttpRequest(
            externalOperation(operation),
            operation.relativePath(),
            query,
            ExternalApiResponseFormat.JSON);
    return new PlaceListSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }

  private static Map<String, String> commonQuery(int pageNo) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", Integer.toString(PlaceListRequestContract.PAGE_SIZE));
    query.put("pageNo", Integer.toString(pageNo));
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    return query;
  }

  private static ExternalApiOperation externalOperation(DiscoveryOperation operation) {
    return switch (operation) {
      case LOCATION -> ExternalApiOperation.TOUR_LOCATION_BASED_LIST;
      case KEYWORD -> ExternalApiOperation.TOUR_SEARCH_KEYWORD;
      case STAY -> ExternalApiOperation.TOUR_SEARCH_STAY;
    };
  }
}
