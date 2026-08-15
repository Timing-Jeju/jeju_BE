package com.timingjeju.api.global.tourapi.place;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.place.PlaceListRequestContract;
import com.timingjeju.api.application.tourapi.place.PlaceListSource;
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
public final class TourApiPlaceListClient implements PlaceListSource {

  private final PlaceListHttpExecutor executor;

  @Autowired
  public TourApiPlaceListClient(ExternalApiExecutor executor) {
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

  TourApiPlaceListClient(PlaceListHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public PlaceListSourceResponse fetch(int pageNo) {
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo는 1 이상이어야 합니다.");
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", Integer.toString(PlaceListRequestContract.PAGE_SIZE));
    query.put("pageNo", Integer.toString(pageNo));
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    query.put("lDongRegnCd", "50");
    PlaceListHttpRequest request =
        new PlaceListHttpRequest(
            ExternalApiOperation.TOUR_AREA_BASED_LIST,
            "/areaBasedList2",
            query,
            ExternalApiResponseFormat.JSON);
    return new PlaceListSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }
}
