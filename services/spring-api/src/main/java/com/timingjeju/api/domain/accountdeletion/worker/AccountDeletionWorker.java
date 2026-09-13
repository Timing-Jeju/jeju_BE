package com.timingjeju.api.domain.accountdeletion.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;

public final class AccountDeletionWorker {

  private static final String UNEXPECTED_FAILURE_CODE = "ACCOUNT_DELETION_INTERNAL_FAILURE";

  private final String workerId;
  private final AccountDeletionWorkRepository repository;
  private final EncryptedSubjectResolver subjectResolver;
  private final GlobalSessionRevoker sessionRevoker;
  private final AccountRequestDenier requestDenier;
  private final ProfileImageDeletion profileImageDeletion;
  private final AppOwnedDataErasure appDataErasure;
  private final SupabaseAuthAdminDeletion authAdminDeletion;
  private final DeletionWorkerPolicy policy;
  private final Clock clock;
  private final DoubleSupplier jitter;

  public AccountDeletionWorker(
      String workerId,
      AccountDeletionWorkRepository repository,
      EncryptedSubjectResolver subjectResolver,
      GlobalSessionRevoker sessionRevoker,
      AccountRequestDenier requestDenier,
      ProfileImageDeletion profileImageDeletion,
      AppOwnedDataErasure appDataErasure,
      SupabaseAuthAdminDeletion authAdminDeletion,
      DeletionWorkerPolicy policy,
      Clock clock,
      DoubleSupplier jitter) {
    if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
      throw new IllegalArgumentException("workerId는 1~100자의 비공백 값이어야 합니다.");
    }
    this.workerId = workerId;
    this.repository = Objects.requireNonNull(repository, "repository는 필수입니다.");
    this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver는 필수입니다.");
    this.sessionRevoker = Objects.requireNonNull(sessionRevoker, "sessionRevoker는 필수입니다.");
    this.requestDenier = Objects.requireNonNull(requestDenier, "requestDenier는 필수입니다.");
    this.profileImageDeletion =
        Objects.requireNonNull(profileImageDeletion, "profileImageDeletion은 필수입니다.");
    this.appDataErasure = Objects.requireNonNull(appDataErasure, "appDataErasure는 필수입니다.");
    this.authAdminDeletion = Objects.requireNonNull(authAdminDeletion, "authAdminDeletion은 필수입니다.");
    this.policy = Objects.requireNonNull(policy, "policy는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.jitter = Objects.requireNonNull(jitter, "jitter는 필수입니다.");
  }

  public void pollOnce() {
    Instant claimedAt = clock.instant();
    List<DeletionLease> leases =
        repository.claimAvailable(
            workerId, claimedAt, policy.leaseDuration(), policy.claimBatchSize());
    for (DeletionLease lease : leases) {
      execute(lease);
    }
  }

  private void execute(DeletionLease lease) {
    try {
      Optional<DeletionWork> loaded = repository.load(lease);
      if (loaded.isEmpty()) {
        return;
      }
      DeletionWork work = loaded.orElseThrow();
      if (work.cancellationRequested() && !work.destructiveStepStarted()) {
        repository.confirmCancelled(lease, clock.instant());
        return;
      }
      if (work.isCompleted(DeletionStep.AUTH_USER_DELETED)) {
        repository.succeed(lease, clock.instant());
        return;
      }

      heartbeatOrLose(lease);
      AuthSubject subject = subjectResolver.resolve(work.requestId(), work.encryptedSubject());
      runStep(lease, work, DeletionStep.SESSIONS_REVOKED, () -> sessionRevoker.revokeAll(subject));
      runStep(
          lease,
          work,
          DeletionStep.ACCOUNT_REQUESTS_DENIED,
          () -> requestDenier.denyFurtherRequests(subject));
      runStep(
          lease,
          work,
          DeletionStep.PROFILE_IMAGES_DELETED,
          () -> profileImageDeletion.deletePrefix(subject.profileImagePrefix()));
      runStep(
          lease,
          work,
          DeletionStep.APP_DATA_ERASED,
          () -> appDataErasure.deleteAndAnonymize(subject));

      heartbeatOrLose(lease);
      startStepOrLose(lease, DeletionStep.AUTH_USER_DELETED);
      authAdminDeletion.deleteUser(subject);
      if (!repository.completeAuthDeletionAndClearSubject(lease, clock.instant())) {
        throw LeaseLost.INSTANCE;
      }
      repository.succeed(lease, clock.instant());
    } catch (LeaseLost ignored) {
      // A newer fencing token owns the request. This worker must stop without state writes.
    } catch (DeletionOperationException failure) {
      handleClassifiedFailure(lease, failure);
    } catch (RuntimeException unexpected) {
      repository.fail(lease, UNEXPECTED_FAILURE_CODE, clock.instant());
    }
  }

  private void runStep(
      DeletionLease lease, DeletionWork work, DeletionStep step, Runnable operation) {
    if (work.isCompleted(step)) {
      return;
    }
    heartbeatOrLose(lease);
    startStepOrLose(lease, step);
    operation.run();
    if (!repository.completeStep(lease, step, clock.instant())) {
      throw LeaseLost.INSTANCE;
    }
  }

  private void heartbeatOrLose(DeletionLease lease) {
    if (!repository.heartbeat(lease, clock.instant(), policy.leaseDuration())) {
      throw LeaseLost.INSTANCE;
    }
  }

  private void startStepOrLose(DeletionLease lease, DeletionStep step) {
    if (!repository.startStep(lease, step, clock.instant())) {
      throw LeaseLost.INSTANCE;
    }
  }

  private void handleClassifiedFailure(DeletionLease lease, DeletionOperationException failure) {
    if (failure.isRetryable() && lease.attempt() < policy.maxAttempts()) {
      repository.retry(
          lease,
          failure.stableFailureCode(),
          clock.instant().plus(policy.retryDelay(lease.attempt(), jitter)),
          clock.instant());
      return;
    }
    repository.fail(lease, failure.stableFailureCode(), clock.instant());
  }

  private static final class LeaseLost extends RuntimeException {
    private static final LeaseLost INSTANCE = new LeaseLost();

    private LeaseLost() {
      super(null, null, false, false);
    }
  }
}
