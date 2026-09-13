package com.timingjeju.api.domain.accountdeletion.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DeletionLeaseState(
    String owner, long fencingToken, int attempt, Instant leaseExpiresAt, Instant nextRetryAt) {

  public DeletionLeaseState {
    validateOwner(owner);
    if (fencingToken <= 0 || attempt <= 0) {
      throw new IllegalArgumentException("fencingToken과 attempt는 양수여야 합니다.");
    }
    Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt은 필수입니다.");
  }

  public static DeletionLeaseState running(
      String owner, long fencingToken, int attempt, Instant leaseExpiresAt, Instant nextRetryAt) {
    return new DeletionLeaseState(owner, fencingToken, attempt, leaseExpiresAt, nextRetryAt);
  }

  public Optional<Claim> claim(
      UUID requestId, String newOwner, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(requestId, "requestId는 필수입니다.");
    validateOwner(newOwner);
    Objects.requireNonNull(now, "now는 필수입니다.");
    requirePositive(leaseDuration);
    boolean leaseExpired = leaseExpiresAt == null || !leaseExpiresAt.isAfter(now);
    boolean retryDue = nextRetryAt == null || !nextRetryAt.isAfter(now);
    if (!leaseExpired || !retryDue) {
      return Optional.empty();
    }
    DeletionLease lease = new DeletionLease(requestId, newOwner, fencingToken + 1, attempt + 1);
    return Optional.of(
        new Claim(
            lease,
            new DeletionLeaseState(
                newOwner, lease.fencingToken(), lease.attempt(), now.plus(leaseDuration), null)));
  }

  public Optional<DeletionLeaseState> heartbeat(
      DeletionLease lease, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(now, "now는 필수입니다.");
    requirePositive(leaseDuration);
    if (!accepts(lease, now)) {
      return Optional.empty();
    }
    return Optional.of(
        new DeletionLeaseState(owner, fencingToken, attempt, now.plus(leaseDuration), nextRetryAt));
  }

  public boolean accepts(DeletionLease lease, Instant now) {
    Objects.requireNonNull(lease, "lease는 필수입니다.");
    Objects.requireNonNull(now, "now는 필수입니다.");
    return owner != null
        && owner.equals(lease.owner())
        && fencingToken == lease.fencingToken()
        && attempt == lease.attempt()
        && leaseExpiresAt != null
        && leaseExpiresAt.isAfter(now);
  }

  private static void validateOwner(String owner) {
    if (owner == null || owner.isBlank() || owner.length() > 100) {
      throw new IllegalArgumentException("owner는 1~100자의 비공백 값이어야 합니다.");
    }
  }

  private static void requirePositive(Duration duration) {
    Objects.requireNonNull(duration, "duration은 필수입니다.");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("duration은 양수여야 합니다.");
    }
  }

  public record Claim(DeletionLease lease, DeletionLeaseState state) {}
}
