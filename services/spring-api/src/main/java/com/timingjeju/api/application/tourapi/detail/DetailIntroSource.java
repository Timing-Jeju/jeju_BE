package com.timingjeju.api.application.tourapi.detail;

public interface DetailIntroSource {
  DetailSourceResponse fetch(String contentId, String contentTypeId);
}
