package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;

public interface DetailInfoSource {
  DetailSourceResponse fetch(String contentId, String contentTypeId);
}
