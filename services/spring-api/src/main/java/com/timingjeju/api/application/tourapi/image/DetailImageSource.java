package com.timingjeju.api.application.tourapi.image;

import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;

@FunctionalInterface
public interface DetailImageSource {
  DetailSourceResponse fetch(String contentId, int pageNo);
}
