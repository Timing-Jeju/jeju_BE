package com.timingjeju.api.application.tago.arrival;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class TagoArrivalDistributedFlightCoordinator implements TagoArrivalFlightCoordinator {
  private final TagoArrivalFlightStore store;
  private final LongSupplier nanoTime;
  private final LongConsumer pause;
  private final Supplier<UUID> owners;
  private final TagoArrivalFlightPolicy policy;

  public TagoArrivalDistributedFlightCoordinator(
      TagoArrivalFlightStore store, TagoArrivalFlightPolicy policy) {
    this(
        store,
        System::nanoTime,
        TagoArrivalDistributedFlightCoordinator::sleep,
        UUID::randomUUID,
        policy);
  }

  TagoArrivalDistributedFlightCoordinator(
      TagoArrivalFlightStore store,
      LongSupplier nanoTime,
      LongConsumer pause,
      Supplier<UUID> owners,
      TagoArrivalFlightPolicy policy) {
    this.store = Objects.requireNonNull(store, "flight store는 필수입니다.");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime은 필수입니다.");
    this.pause = Objects.requireNonNull(pause, "pause는 필수입니다.");
    this.owners = Objects.requireNonNull(owners, "owner supplier는 필수입니다.");
    this.policy = Objects.requireNonNull(policy, "flight policy는 필수입니다.");
  }

  @Override
  public TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key, Supplier<TagoArrivalSnapshot> coordinatedAction) {
    return coalesce(key, coordinatedAction, coordinatedAction);
  }

  @Override
  public TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key,
      Supplier<TagoArrivalSnapshot> leaderAction,
      Supplier<TagoArrivalSnapshot> replayAction) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Objects.requireNonNull(leaderAction, "leaderAction은 필수입니다.");
    Objects.requireNonNull(replayAction, "replayAction은 필수입니다.");
    long startedAt = nanoTime.getAsLong();
    String fingerprint = fingerprint(key);

    while (true) {
      requireBeforeDeadline(startedAt);
      UUID proposedOwner = Objects.requireNonNull(owners.get(), "owner는 필수입니다.");
      TagoArrivalFlightDecision decision =
          store.observeOrClaim(fingerprint, proposedOwner, policy.lease(), policy.quarantine());
      if (elapsed(startedAt) >= policy.deadline().toNanos()) {
        if (decision.status() == TagoArrivalFlightStatus.LEADER) {
          requireMutation(store.abandon(decision.lease(), policy.quarantine()));
        }
        throw TagoArrivalException.dataUnavailable();
      }

      switch (decision.status()) {
        case LEADER:
          return executeLeader(decision.lease(), leaderAction);
        case SUCCEEDED:
          return replayAction.get();
        case FAILED, ABANDONED:
          throw TagoArrivalException.fromCode(decision.outcome().orElseThrow());
        case RUNNING:
          long remaining = policy.deadline().toNanos() - elapsed(startedAt);
          if (remaining <= 0) throw TagoArrivalException.dataUnavailable();
          pause.accept(Math.min(policy.backoff().toNanos(), remaining));
          requireNotInterrupted();
      }
    }
  }

  static String fingerprint(TagoArrivalCacheKey key) {
    String canonical =
        component(key.provider())
            + component(key.service())
            + component(key.cityCode())
            + component(key.stopId().toString())
            + component(key.nodeId());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
    }
  }

  private TagoArrivalSnapshot executeLeader(
      TagoArrivalFlightLease lease, Supplier<TagoArrivalSnapshot> action) {
    try {
      TagoArrivalSnapshot result =
          Objects.requireNonNull(action.get(), "flight action result는 필수입니다.");
      requireMutation(store.completeSuccess(lease, policy.retain()));
      return result;
    } catch (TagoArrivalException failure) {
      requireMutation(store.completeFailure(lease, failure.code(), policy.retain()));
      throw failure;
    } catch (RuntimeException programmerFailure) {
      try {
        store.completeFailure(lease, TagoArrivalException.Code.DATA_UNAVAILABLE, policy.retain());
      } catch (TagoArrivalException ignored) {
        // leader에는 원래 programmer failure를 보존하고 follower는 lease expiry 뒤 fail-closed한다.
      }
      throw programmerFailure;
    }
  }

  private void requireBeforeDeadline(long startedAt) {
    requireNotInterrupted();
    if (elapsed(startedAt) >= policy.deadline().toNanos()) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private long elapsed(long startedAt) {
    long elapsed = nanoTime.getAsLong() - startedAt;
    return elapsed < 0 ? Long.MAX_VALUE : elapsed;
  }

  private static void requireMutation(boolean updated) {
    if (!updated) throw TagoArrivalException.dataUnavailable();
  }

  private static String component(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return bytes.length + ":" + value;
  }

  private static void sleep(long nanos) {
    try {
      TimeUnit.NANOSECONDS.sleep(nanos);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) throw TagoArrivalException.dataUnavailable();
  }
}
