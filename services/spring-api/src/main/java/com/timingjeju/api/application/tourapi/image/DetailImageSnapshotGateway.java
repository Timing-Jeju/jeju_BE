package com.timingjeju.api.application.tourapi.image;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.util.UUID;

public interface DetailImageSnapshotGateway {
  SavedDetailImagePage save(
      UUID importRunId, String contentId, int pageNo, DetailSourceResponse response);

  void markParsed(SavedDetailImagePage page);

  void markRejected(SavedDetailImagePage page);
}
