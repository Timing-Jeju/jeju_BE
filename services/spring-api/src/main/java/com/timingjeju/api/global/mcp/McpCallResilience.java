package com.timingjeju.api.global.mcp;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class McpCallResilience {

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int maxAttempts;
  private final Duration retryDelay;
  private final int circuitFailureThreshold;
  private final Duration circuitOpenDuration;
  private final LongSupplier nanoTime;
  private final Sleeper sleeper;
  private State state = State.CLOSED;
  private int consecutiveFailures;
  private long openedAtNanos;
  private boolean halfOpenInFlight;

  McpCallResilience(
      int maxAttempts,
      Duration retryDelay,
      int circuitFailureThreshold,
      Duration circuitOpenDuration,
      LongSupplier nanoTime,
      Sleeper sleeper) {
    if (maxAttempts < 1
        || maxAttempts > 5
        || circuitFailureThreshold < 1
        || retryDelay == null
        || retryDelay.isNegative()
        || circuitOpenDuration == null
        || circuitOpenDuration.isZero()
        || circuitOpenDuration.isNegative()) {
      throw new IllegalArgumentException("MCP 복원력 정책 값이 유효하지 않습니다.");
    }
    this.maxAttempts = maxAttempts;
    this.retryDelay = retryDelay;
    this.circuitFailureThreshold = circuitFailureThreshold;
    this.circuitOpenDuration = circuitOpenDuration;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime은 필수입니다.");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper는 필수입니다.");
  }

  static McpCallResilience defaults() {
    return new McpCallResilience(
        3,
        Duration.ofMillis(200),
        5,
        Duration.ofSeconds(30),
        System::nanoTime,
        duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000));
  }

  <T> McpResilientResult<T> execute(Supplier<T> operation) {
    Objects.requireNonNull(operation, "operation은 필수입니다.");
    boolean halfOpen = acquire();
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        T value = operation.get();
        recordSuccess(halfOpen);
        return new McpResilientResult<>(value, attempt);
      } catch (McpContractException exception) {
        recordFailure(halfOpen);
        throw exception;
      } catch (RuntimeException exception) {
        if (attempt == maxAttempts) {
          recordFailure(halfOpen);
          throw exception;
        }
        sleepBeforeRetry(attempt, halfOpen);
      }
    }
    throw new IllegalStateException("도달할 수 없는 MCP retry 상태입니다.");
  }

  private synchronized boolean acquire() {
    if (state == State.OPEN) {
      long elapsed = Math.max(0L, nanoTime.getAsLong() - openedAtNanos);
      if (elapsed < circuitOpenDuration.toNanos()) {
        throw new McpRemoteCallException("MCP_CIRCUIT_OPEN");
      }
      state = State.HALF_OPEN;
      halfOpenInFlight = false;
    }
    if (state == State.HALF_OPEN) {
      if (halfOpenInFlight) throw new McpRemoteCallException("MCP_CIRCUIT_OPEN");
      halfOpenInFlight = true;
      return true;
    }
    return false;
  }

  private synchronized void recordSuccess(boolean halfOpen) {
    if (halfOpen && state != State.HALF_OPEN) return;
    state = State.CLOSED;
    consecutiveFailures = 0;
    halfOpenInFlight = false;
  }

  private synchronized void recordFailure(boolean halfOpen) {
    if (halfOpen) {
      open();
      return;
    }
    if (state != State.CLOSED) return;
    consecutiveFailures++;
    if (consecutiveFailures >= circuitFailureThreshold) open();
  }

  private void sleepBeforeRetry(int attempt, boolean halfOpen) {
    try {
      sleeper.sleep(retryDelay.multipliedBy(attempt));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      recordFailure(halfOpen);
      throw new McpRemoteCallException("MCP_RETRY_INTERRUPTED", exception);
    }
  }

  private void open() {
    state = State.OPEN;
    openedAtNanos = nanoTime.getAsLong();
    consecutiveFailures = 0;
    halfOpenInFlight = false;
  }
}
