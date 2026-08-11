package com.timingjeju.api.application.asyncrun;

public final class RunLeaseLostException extends RuntimeException {

  public RunLeaseLostException() {
    super("비동기 run의 lease 또는 fencing 권한을 상실했습니다.");
  }
}
