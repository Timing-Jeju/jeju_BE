package com.timingjeju.api.application.tago.stop;

public interface TagoStopSource {
  TagoStopSourceResponse fetchCityCodes();

  TagoStopSourceResponse fetchStations(String cityCode, int pageNo);
}
