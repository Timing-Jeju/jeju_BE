package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.util.UUID;

public interface DetailInfoSnapshotGateway {
  SavedDetailInfoPage save(
      UUID importRunId,
      String contentId,
      String contentTypeId,
      int pageNo,
      DetailSourceResponse response);

  void markParsed(SavedDetailInfoPage page);

  void markRejected(SavedDetailInfoPage page);
}
