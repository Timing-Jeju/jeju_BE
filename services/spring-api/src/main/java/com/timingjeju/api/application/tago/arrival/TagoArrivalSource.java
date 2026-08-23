package com.timingjeju.api.application.tago.arrival;

public interface TagoArrivalSource {
  TagoArrivalSourceResponse fetch(String cityCode, String nodeId);
}
