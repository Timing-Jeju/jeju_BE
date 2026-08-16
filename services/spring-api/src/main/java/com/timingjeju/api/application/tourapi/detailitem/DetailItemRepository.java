package com.timingjeju.api.application.tourapi.detailitem;

public interface DetailItemRepository {
  DetailItemSyncResult sync(DetailItemSyncCommand command);
}
