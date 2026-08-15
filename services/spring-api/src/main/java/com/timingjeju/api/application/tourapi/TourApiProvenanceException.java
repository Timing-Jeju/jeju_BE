package com.timingjeju.api.application.tourapi;

public final class TourApiProvenanceException extends RuntimeException {

  public TourApiProvenanceException() {
    super("TourAPI 원천 계보를 기록할 수 없습니다.");
  }
}
