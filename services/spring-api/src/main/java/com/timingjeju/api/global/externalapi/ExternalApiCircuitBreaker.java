package com.timingjeju.api.global.externalapi;

import java.util.ArrayDeque;
import java.util.Deque;

final class ExternalApiCircuitBreaker {

  enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  record Permit(boolean halfOpen) {}

  private final ExternalApiResiliencePolicy policy;
  private final ExternalApiTimeSource timeSource;
  private final Deque<Boolean> failures = new ArrayDeque<>();
  private State state = State.CLOSED;
  private long openedAtNanos;
  private int halfOpenInFlight;
  private int halfOpenCompleted;

  ExternalApiCircuitBreaker(ExternalApiResiliencePolicy policy, ExternalApiTimeSource timeSource) {
    this.policy = policy;
    this.timeSource = timeSource;
  }

  synchronized Permit acquire() {
    if (state == State.OPEN) {
      if (elapsedSinceOpen() < policy.circuitOpenDuration().toNanos()) {
        throw new ExternalApiCircuitOpenException();
      }
      state = State.HALF_OPEN;
      halfOpenInFlight = 0;
      halfOpenCompleted = 0;
    }
    if (state == State.HALF_OPEN) {
      if (halfOpenCompleted + halfOpenInFlight >= policy.circuitHalfOpenCalls()) {
        throw new ExternalApiCircuitOpenException();
      }
      halfOpenInFlight++;
      return new Permit(true);
    }
    return new Permit(false);
  }

  synchronized void record(Permit permit, boolean failure) {
    if (permit.halfOpen()) {
      if (state != State.HALF_OPEN || halfOpenInFlight == 0) {
        return;
      }
      halfOpenInFlight--;
      halfOpenCompleted++;
      if (failure) {
        open();
      } else if (halfOpenCompleted == policy.circuitHalfOpenCalls() && halfOpenInFlight == 0) {
        close();
      }
      return;
    }
    if (state != State.CLOSED) {
      return;
    }
    failures.addLast(failure);
    if (failures.size() > policy.circuitWindowSize()) {
      failures.removeFirst();
    }
    if (failures.size() >= policy.circuitMinimumCalls()) {
      long failureCount = failures.stream().filter(Boolean::booleanValue).count();
      if ((double) failureCount / failures.size() >= policy.circuitFailureRate()) {
        open();
      }
    }
  }

  synchronized State state() {
    if (state == State.OPEN && elapsedSinceOpen() >= policy.circuitOpenDuration().toNanos()) {
      return State.HALF_OPEN;
    }
    return state;
  }

  private long elapsedSinceOpen() {
    return Math.max(0L, timeSource.nanoTime() - openedAtNanos);
  }

  private void open() {
    state = State.OPEN;
    openedAtNanos = timeSource.nanoTime();
    halfOpenInFlight = 0;
    halfOpenCompleted = 0;
  }

  private void close() {
    state = State.CLOSED;
    failures.clear();
    halfOpenInFlight = 0;
    halfOpenCompleted = 0;
  }
}
