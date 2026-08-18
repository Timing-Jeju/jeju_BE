package com.timingjeju.api.global.tourapi.detailitem;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoRequestContract;
import com.timingjeju.api.application.tourapi.detailitem.DetailInfoSource;
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
public final class TourApiDetailInfoClient implements DetailInfoSource {
  private final DetailInfoHttpExecutor executor;

  @Autowired
  public TourApiDetailInfoClient(ExternalApiExecutor executor) {
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

  TourApiDetailInfoClient(DetailInfoHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public DetailSourceResponse fetch(String contentId, String contentTypeId, int pageNo) {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()
        || pageNo < 1) {
      throw new IllegalArgumentException("contentId와 contentTypeId, pageNo는 필수입니다.");
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    query.put("contentId", contentId);
    query.put("contentTypeId", contentTypeId);
    query.put("pageNo", Integer.toString(pageNo));
    query.put("numOfRows", Integer.toString(DetailInfoRequestContract.PAGE_SIZE));
    var request =
        new DetailInfoHttpRequest(
            ExternalApiOperation.TOUR_DETAIL_INFO,
            "detailInfo2",
            query,
            ExternalApiResponseFormat.JSON);
    return new DetailSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }
}
