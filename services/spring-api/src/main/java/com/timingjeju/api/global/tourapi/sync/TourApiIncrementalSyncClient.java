package com.timingjeju.api.global.tourapi.sync;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncCursor;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncRequestContract;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSource;
import com.timingjeju.api.application.tourapi.sync.IncrementalSyncSourceResponse;
import com.timingjeju.api.global.externalapi.ExternalApiExecutor;
import com.timingjeju.api.global.externalapi.ExternalApiOperation;
import com.timingjeju.api.global.externalapi.ExternalApiRequest;
import com.timingjeju.api.global.externalapi.ExternalApiResponseFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TourApiIncrementalSyncClient implements IncrementalSyncSource {
  private static final DateTimeFormatter CURSOR_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Seoul"));
  private final IncrementalSyncHttpExecutor executor;

  @Autowired
  public TourApiIncrementalSyncClient(ExternalApiExecutor executor) {
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

  TourApiIncrementalSyncClient(IncrementalSyncHttpExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor는 필수입니다.");
  }

  @Override
  public IncrementalSyncSourceResponse fetch(IncrementalSyncCursor cursor, int pageNo) {
    Objects.requireNonNull(cursor, "cursor는 필수입니다.");
    if (pageNo < 1) {
      throw new IllegalArgumentException("pageNo는 1 이상이어야 합니다.");
    }
    Map<String, String> query = new LinkedHashMap<>();
    query.put("numOfRows", Integer.toString(IncrementalSyncRequestContract.PAGE_SIZE));
    query.put("pageNo", Integer.toString(pageNo));
    query.put("MobileOS", "ETC");
    query.put("MobileApp", "TimingJeju");
    query.put("_type", "json");
    query.put("lDongRegnCd", "50");
    query.put("modifiedtime", CURSOR_FORMAT.format(cursor.modifiedAfter()));
    IncrementalSyncHttpRequest request =
        new IncrementalSyncHttpRequest(
            ExternalApiOperation.TOUR_AREA_SYNC,
            "areaBasedSyncList2",
            query,
            ExternalApiResponseFormat.JSON);
    return new IncrementalSyncSourceResponse(executor.execute(request), SnapshotPayloadFormat.JSON);
  }
}
