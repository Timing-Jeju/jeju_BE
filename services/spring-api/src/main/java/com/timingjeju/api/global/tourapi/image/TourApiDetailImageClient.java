package com.timingjeju.api.global.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.image.DetailImageRequestContract;
import com.timingjeju.api.application.tourapi.image.DetailImageSource;
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
public final class TourApiDetailImageClient implements DetailImageSource {
  private final DetailImageHttpExecutor executor;

  @Autowired
  public TourApiDetailImageClient(ExternalApiExecutor executor) {
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

  TourApiDetailImageClient(DetailImageHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public DetailSourceResponse fetch(String contentId, int pageNo) {
    if (contentId == null || contentId.isBlank() || pageNo < 1) {
      throw new IllegalArgumentException("contentId와 pageNo는 필수입니다.");
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    query.put("contentId", contentId);
    query.put("imageYN", "Y");
    query.put("subImageYN", "Y");
    query.put("pageNo", Integer.toString(pageNo));
    query.put("numOfRows", Integer.toString(DetailImageRequestContract.PAGE_SIZE));
    DetailImageHttpRequest request =
        new DetailImageHttpRequest(
            ExternalApiOperation.TOUR_DETAIL_IMAGE,
            "/detailImage2",
            query,
            ExternalApiResponseFormat.JSON);
    return new DetailSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }
}
