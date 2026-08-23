package com.timingjeju.api.global.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailCommonSource;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
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
public final class TourApiDetailCommonClient implements DetailCommonSource {
  private final PlaceDetailHttpExecutor executor;

  @Autowired
  public TourApiDetailCommonClient(ExternalApiExecutor executor) {
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

  TourApiDetailCommonClient(PlaceDetailHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public DetailSourceResponse fetch(String contentId) {
    Map<String, String> query = base(contentId);
    var request =
        new PlaceDetailHttpRequest(
            ExternalApiOperation.TOUR_DETAIL_COMMON,
            "detailCommon2",
            query,
            ExternalApiResponseFormat.JSON);
    return new DetailSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }

  static Map<String, String> base(String contentId) {
    if (contentId == null || contentId.isBlank())
      throw new IllegalArgumentException("contentId는 필수입니다.");
    Map<String, String> query = new LinkedHashMap<>();
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    query.put("contentId", contentId);
    return query;
  }
}
