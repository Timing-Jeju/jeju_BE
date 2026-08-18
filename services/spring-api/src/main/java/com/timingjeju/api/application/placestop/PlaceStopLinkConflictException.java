package com.timingjeju.api.application.placestop;

public final class PlaceStopLinkConflictException extends RuntimeException {
  public PlaceStopLinkConflictException() {
    super("동일하거나 이전 관측 시각의 place-stop scope가 현재 상태와 충돌합니다.");
  }
}
