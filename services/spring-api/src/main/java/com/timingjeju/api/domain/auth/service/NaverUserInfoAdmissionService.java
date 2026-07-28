package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 공개 Naver UserInfo adapter의 인스턴스별 호출량과 동시 outbound 호출 수를 제한한다. */
public final class NaverUserInfoAdmissionService {

  private static final int PRODUCTION_REQUESTS_PER_WINDOW = 60;
  private static final Duration PRODUCTION_WINDOW = Duration.ofSeconds(1);
  private static final int PRODUCTION_MAX_CONCURRENT_REQUESTS = 8;

  private final int requestsPerWindow;
  private final long windowNanos;
  private final Semaphore concurrentRequests;
  private final LongSupplier ticker;
  private long windowStartedAt;
  private int requestCount;

  private NaverUserInfoAdmissionService(
      int requestsPerWindow, Duration window, int maxConcurrentRequests, LongSupplier ticker) {
    if (requestsPerWindow < 1
        || window.isZero()
        || window.isNegative()
        || maxConcurrentRequests < 1) {
      throw new IllegalArgumentException("Naver UserInfo admission control 설정이 올바르지 않습니다.");
    }
    this.requestsPerWindow = requestsPerWindow;
    this.windowNanos = window.toNanos();
    this.concurrentRequests = new Semaphore(maxConcurrentRequests);
    this.ticker = ticker;
    this.windowStartedAt = ticker.getAsLong();
  }

  public static NaverUserInfoAdmissionService production() {
    return new NaverUserInfoAdmissionService(
        PRODUCTION_REQUESTS_PER_WINDOW,
        PRODUCTION_WINDOW,
        PRODUCTION_MAX_CONCURRENT_REQUESTS,
        System::nanoTime);
  }

  static NaverUserInfoAdmissionService forTest(
      int requestsPerWindow, Duration window, int maxConcurrentRequests, LongSupplier ticker) {
    return new NaverUserInfoAdmissionService(
        requestsPerWindow, window, maxConcurrentRequests, ticker);
  }

  public <T> T execute(Supplier<T> outboundCall) {
    reserveRateLimitSlot();
    if (!concurrentRequests.tryAcquire()) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.APPLICATION_OVERLOADED);
    }
    try {
      return outboundCall.get();
    } finally {
      concurrentRequests.release();
    }
  }

  private synchronized void reserveRateLimitSlot() {
    long now = ticker.getAsLong();
    if (now < windowStartedAt || now - windowStartedAt >= windowNanos) {
      windowStartedAt = now;
      requestCount = 0;
    }
    if (requestCount >= requestsPerWindow) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.APPLICATION_RATE_LIMITED);
    }
    requestCount++;
  }
}
