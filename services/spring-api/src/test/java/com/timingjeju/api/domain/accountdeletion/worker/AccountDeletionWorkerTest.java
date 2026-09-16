package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class AccountDeletionWorkerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
  private static final String REQUEST_ID = "01K4V106000000000000000001";
  private static final DeletionLease LEASE = new DeletionLease(REQUEST_ID, "worker-106", 7, 1);
  private static final EncryptedAuthSubject ENCRYPTED_SUBJECT =
      new EncryptedAuthSubject("ciphertext-106", "key-v2");

  @Test
  void 유효한_claim은_고정_순서로_삭제하고_auth_성공과_함께_subject를_제거한다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .containsExactly(
            "claim",
            "load",
            "heartbeat",
            "resolve:key-v2",
            "heartbeat",
            "start:SESSIONS_REVOKED",
            "verify-pending-deny",
            "complete:SESSIONS_REVOKED",
            "heartbeat",
            "start:ACCOUNT_REQUESTS_DENIED",
            "deny-account-requests",
            "complete:ACCOUNT_REQUESTS_DENIED",
            "heartbeat",
            "start:PROFILE_IMAGES_DELETED",
            "heartbeat",
            "delete-storage:profile-images/auth-subject-106",
            "complete:PROFILE_IMAGES_DELETED",
            "heartbeat",
            "start:APP_DATA_ERASED",
            "delete-and-anonymize-app-data",
            "complete:APP_DATA_ERASED",
            "heartbeat",
            "start:AUTH_USER_DELETED",
            "heartbeat",
            "delete-auth-user",
            "complete-auth-and-clear-subject",
            "succeed");
    assertThat(fixture.repository.subjectCleared).isTrue();
    assertThat(fixture.repository.succeeded).isTrue();
    assertThat(fixture.repository.claimOwner).isEqualTo("worker-106");
    assertThat(fixture.repository.claimLeaseDuration).isEqualTo(Duration.ofSeconds(30));
    assertThat(fixture.repository.claimLimit).isOne();
  }

  @Test
  void 파괴_단계_전_cancelled_요청은_subject_복호화나_외부_삭제_없이_terminal로_확정한다() {
    Fixture fixture = fixture(work(true, Set.of()), LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.events).containsExactly("claim", "load", "cancel");
    assertThat(fixture.repository.cancelled).isTrue();
  }

  @Test
  void deny_확인만_완료된_요청은_아직_cancel할_수_있다() {
    Fixture fixture =
        fixture(
            work(
                true,
                EnumSet.of(DeletionStep.SESSIONS_REVOKED, DeletionStep.ACCOUNT_REQUESTS_DENIED)),
            LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.events).containsExactly("claim", "load", "cancel");
  }

  @Test
  void 첫_Storage_start_marker_뒤에는_cancel대신_삭제를_resume한다() {
    DeletionWork marked =
        new DeletionWork(
            REQUEST_ID,
            true,
            true,
            EnumSet.of(DeletionStep.SESSIONS_REVOKED, DeletionStep.ACCOUNT_REQUESTS_DENIED),
            ENCRYPTED_SUBJECT);
    Fixture fixture = fixture(marked, LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .doesNotContain("cancel")
        .contains("delete-storage:profile-images/auth-subject-106");
  }

  @Test
  void 재시작은_완료된_단계를_건너뛰고_실패했던_단계부터_재개한다() {
    Set<DeletionStep> completed =
        EnumSet.of(
            DeletionStep.SESSIONS_REVOKED,
            DeletionStep.ACCOUNT_REQUESTS_DENIED,
            DeletionStep.PROFILE_IMAGES_DELETED);
    Fixture fixture = fixture(work(false, completed), LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .containsSubsequence(
            "resolve:key-v2",
            "start:APP_DATA_ERASED",
            "delete-and-anonymize-app-data",
            "complete:APP_DATA_ERASED",
            "delete-auth-user",
            "complete-auth-and-clear-subject",
            "succeed")
        .doesNotContain("verify-pending-deny", "deny-account-requests")
        .noneMatch(event -> event.startsWith("delete-storage:"));
  }

  @Test
  void retryable_장애는_분류된_code와_backoff로_running_요청을_예약한다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.operations.failureAt = "delete-storage";
    fixture.operations.failure = DeletionOperationException.retryable("STORAGE_TEMPORARY");

    fixture.worker.pollOnce();

    assertThat(fixture.repository.retryCode).isEqualTo("STORAGE_TEMPORARY");
    assertThat(fixture.repository.nextRetryAt).isEqualTo(NOW.plusMillis(500));
    assertThat(fixture.repository.failed).isFalse();
    assertThat(fixture.events).doesNotContain("delete-and-anonymize-app-data", "delete-auth-user");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "resolve",
        "verify-pending-deny",
        "deny-account-requests",
        "delete-storage",
        "delete-app-data",
        "delete-auth-user"
      })
  void 모든_외부_단계의_retryable_장애는_같은_안정_code로_재시도한다(String failedOperation) {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.operations.failureAt = failedOperation;
    fixture.operations.failure = DeletionOperationException.retryable("PROVIDER_TEMPORARY");

    fixture.worker.pollOnce();

    assertThat(fixture.repository.retryCode).isEqualTo("PROVIDER_TEMPORARY");
    assertThat(fixture.repository.failed).isFalse();
  }

  @Test
  void 최대_시도에서_retryable_장애는_terminal_failed가_된다() {
    Fixture fixture =
        fixture(work(false, Set.of()), new DeletionLease(REQUEST_ID, "worker-106", 8, 5));
    fixture.operations.failureAt = "verify-pending-deny";
    fixture.operations.failure = DeletionOperationException.retryable("AUTH_TEMPORARY");

    fixture.worker.pollOnce();

    assertThat(fixture.repository.failureCode).isEqualTo("AUTH_TEMPORARY");
    assertThat(fixture.repository.failed).isTrue();
    assertThat(fixture.repository.nextRetryAt).isNull();
  }

  @Test
  void terminal_장애는_첫_시도에도_즉시_failed가_되고_raw_원인은_저장하지_않는다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.operations.failureAt = "resolve";
    fixture.operations.failure =
        DeletionOperationException.terminal("SUBJECT_DECRYPTION_FAILED", new Error("raw-secret"));

    fixture.worker.pollOnce();

    assertThat(fixture.repository.failureCode).isEqualTo("SUBJECT_DECRYPTION_FAILED");
    assertThat(fixture.events).noneMatch(event -> event.contains("raw-secret"));
  }

  @Test
  void heartbeat가_lease_상실을_알리면_외부_호출과_terminal_write를_하지_않는다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.repository.heartbeatAccepted = false;

    fixture.worker.pollOnce();

    assertThat(fixture.events).containsExactly("claim", "load", "heartbeat");
    assertThat(fixture.repository.hasTerminalWrite()).isFalse();
  }

  @Test
  void 삼십초를_넘는_Storage_pagination은_주기_checkpoint하고_lease상실_즉시_추가호출을_멈춘다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.operations.storageCheckpoints = 3;
    fixture.repository.rejectHeartbeatAfter = 5;

    fixture.worker.pollOnce();

    assertThat(fixture.repository.heartbeatCalls).isEqualTo(6);
    assertThat(fixture.events).doesNotContain("delete-storage:profile-images/auth-subject-106");
    assertThat(fixture.repository.hasTerminalWrite()).isFalse();
  }

  @Test
  void Auth_HTTP_진입후_lease_checkpoint_상실은_delete와_terminal_write를_중단한다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.repository.rejectHeartbeatAfter = 7;

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .containsSubsequence("start:AUTH_USER_DELETED", "heartbeat")
        .doesNotContain("delete-auth-user", "complete-auth-and-clear-subject", "succeed");
    assertThat(fixture.repository.hasTerminalWrite()).isFalse();
  }

  @Test
  void stale_fencing_token으로_step_완료가_거부되면_후속_삭제와_terminal_write를_하지_않는다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.repository.rejectedStep = DeletionStep.SESSIONS_REVOKED;

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .containsExactly(
            "claim",
            "load",
            "heartbeat",
            "resolve:key-v2",
            "heartbeat",
            "start:SESSIONS_REVOKED",
            "verify-pending-deny",
            "complete:SESSIONS_REVOKED");
    assertThat(fixture.repository.hasTerminalWrite()).isFalse();
  }

  @Test
  void auth_삭제_직후_fence가_바뀌면_stale_worker는_subject_clear나_success를_확정하지_못한다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.repository.authCompletionAccepted = false;

    fixture.worker.pollOnce();

    assertThat(fixture.events).contains("delete-auth-user", "complete-auth-and-clear-subject");
    assertThat(fixture.repository.subjectCleared).isFalse();
    assertThat(fixture.repository.succeeded).isFalse();
  }

  @Test
  void step_시작_기록이_stale_fence로_거부되면_외부_동작을_호출하지_않는다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);
    fixture.repository.rejectedStartStep = DeletionStep.SESSIONS_REVOKED;

    fixture.worker.pollOnce();

    assertThat(fixture.events)
        .containsExactly(
            "claim", "load", "heartbeat", "resolve:key-v2", "heartbeat", "start:SESSIONS_REVOKED");
    assertThat(fixture.events).doesNotContain("verify-pending-deny");
  }

  @Test
  void 외부_호출은_repository의_짧은_상태_변경_경계_밖에서_실행된다() {
    Fixture fixture = fixture(work(false, Set.of()), LEASE);

    fixture.worker.pollOnce();

    assertThat(fixture.operations.observedRepositoryMutation).isFalse();
  }

  @Test
  void 만료된_running_lease는_새_owner가_회수하고_이전_fencing_token을_무효화한다() {
    DeletionLeaseState expired =
        DeletionLeaseState.running("old-worker", 11, 2, NOW.minusSeconds(1), null);

    DeletionLeaseState.Claim claim =
        expired.claim(REQUEST_ID, "new-worker", NOW, Duration.ofSeconds(30)).orElseThrow();

    assertThat(claim.lease().fencingToken()).isEqualTo(12);
    assertThat(claim.lease().attempt()).isEqualTo(3);
    assertThat(claim.state().accepts(new DeletionLease(REQUEST_ID, "old-worker", 11, 2), NOW))
        .isFalse();
    assertThat(claim.state().accepts(claim.lease(), NOW)).isTrue();
  }

  @Test
  void 만료_직전_heartbeat는_현재_fence만_연장하고_stale_heartbeat는_거부한다() {
    DeletionLeaseState running =
        DeletionLeaseState.running("worker-106", 20, 1, NOW.plusMillis(1), null);
    DeletionLease current = new DeletionLease(REQUEST_ID, "worker-106", 20, 1);

    Optional<DeletionLeaseState> renewed = running.heartbeat(current, NOW, Duration.ofSeconds(30));

    assertThat(renewed)
        .get()
        .extracting(DeletionLeaseState::leaseExpiresAt)
        .isEqualTo(NOW.plusSeconds(30));
    assertThat(
            running.heartbeat(
                new DeletionLease(REQUEST_ID, "worker-106", 19, 1), NOW, Duration.ofSeconds(30)))
        .isEmpty();
  }

  @Test
  void next_retry_at_전에는_만료된_lease도_claim하지_않는다() {
    DeletionLeaseState waiting =
        DeletionLeaseState.running("worker-106", 20, 1, NOW.minusSeconds(1), NOW.plusSeconds(1));

    assertThat(waiting.claim(REQUEST_ID, "new-worker", NOW, Duration.ofSeconds(30))).isEmpty();
    assertThat(waiting.claim(REQUEST_ID, "new-worker", NOW.plusSeconds(1), Duration.ofSeconds(30)))
        .isPresent();
  }

  @Test
  void 민감한_subject와_ciphertext는_to_string에_노출하지_않는다() {
    assertThat(AuthSubject.of("secret-sub").toString()).doesNotContain("secret-sub");
    assertThat(new EncryptedAuthSubject("cipher-secret", "v1").toString())
        .doesNotContain("cipher-secret");
  }

  private static DeletionWork work(boolean cancellationRequested, Set<DeletionStep> completed) {
    return new DeletionWork(REQUEST_ID, cancellationRequested, completed, ENCRYPTED_SUBJECT);
  }

  private static Fixture fixture(DeletionWork work, DeletionLease lease) {
    List<String> events = new ArrayList<>();
    RecordingRepository repository = new RecordingRepository(events, work, lease);
    RecordingOperations operations = new RecordingOperations(events, repository);
    AccountDeletionWorker worker =
        new AccountDeletionWorker(
            "worker-106",
            repository,
            operations,
            operations,
            operations,
            operations,
            operations,
            operations,
            DeletionWorkerPolicy.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> 0.5d);
    return new Fixture(events, repository, operations, worker);
  }

  private record Fixture(
      List<String> events,
      RecordingRepository repository,
      RecordingOperations operations,
      AccountDeletionWorker worker) {}

  private static final class RecordingOperations
      implements EncryptedSubjectResolver,
          GlobalSessionRevoker,
          AccountRequestDenier,
          ProfileImageDeletion,
          AppOwnedDataErasure,
          SupabaseAuthAdminDeletion {
    private final List<String> events;
    private final RecordingRepository repository;
    private boolean observedRepositoryMutation;
    private String failureAt;
    private DeletionOperationException failure;
    private int storageCheckpoints = 1;

    private RecordingOperations(List<String> events, RecordingRepository repository) {
      this.events = events;
      this.repository = repository;
    }

    @Override
    public AuthSubject resolve(String requestId, EncryptedAuthSubject encryptedSubject) {
      external("resolve", "resolve:" + encryptedSubject.keyVersion());
      return AuthSubject.of("auth-subject-106");
    }

    @Override
    public void revokeAll(AuthSubject subject) {
      external("verify-pending-deny", "verify-pending-deny");
    }

    @Override
    public void denyFurtherRequests(AuthSubject subject) {
      external("deny-account-requests", "deny-account-requests");
    }

    @Override
    public void deletePrefix(String objectPrefix) {
      external("delete-storage", "delete-storage:" + objectPrefix);
    }

    @Override
    public void deletePrefix(String objectPrefix, Runnable leaseCheckpoint) {
      for (int index = 0; index < storageCheckpoints; index++) {
        leaseCheckpoint.run();
      }
      deletePrefix(objectPrefix);
    }

    @Override
    public void deleteAndAnonymize(DeletionLease lease, AuthSubject subject) {
      external("delete-app-data", "delete-and-anonymize-app-data");
    }

    @Override
    public void deleteUser(AuthSubject subject) {
      external("delete-auth-user", "delete-auth-user");
    }

    private void external(String operation, String event) {
      observedRepositoryMutation |= repository.inMutation;
      events.add(event);
      if (operation.equals(failureAt)) {
        throw failure;
      }
    }
  }

  private static final class RecordingRepository implements AccountDeletionWorkRepository {
    private final List<String> events;
    private final DeletionWork work;
    private final DeletionLease lease;
    private boolean inMutation;
    private String claimOwner;
    private Duration claimLeaseDuration;
    private int claimLimit;
    private boolean heartbeatAccepted = true;
    private boolean authCompletionAccepted = true;
    private DeletionStep rejectedStep;
    private DeletionStep rejectedStartStep;
    private boolean subjectCleared;
    private boolean succeeded;
    private boolean failed;
    private boolean cancelled;
    private String retryCode;
    private String failureCode;
    private Instant nextRetryAt;
    private int heartbeatCalls;
    private int rejectHeartbeatAfter = Integer.MAX_VALUE;

    private RecordingRepository(List<String> events, DeletionWork work, DeletionLease lease) {
      this.events = events;
      this.work = work;
      this.lease = lease;
    }

    @Override
    public List<DeletionLease> claimAvailable(
        String owner, Instant now, Duration leaseDuration, int limit) {
      claimOwner = owner;
      claimLeaseDuration = leaseDuration;
      claimLimit = limit;
      return mutation("claim", List.of(lease));
    }

    @Override
    public Optional<DeletionWork> load(DeletionLease lease) {
      return mutation("load", Optional.of(work));
    }

    @Override
    public boolean heartbeat(DeletionLease lease, Instant now, Duration leaseDuration) {
      heartbeatCalls++;
      return mutation("heartbeat", heartbeatAccepted && heartbeatCalls <= rejectHeartbeatAfter);
    }

    @Override
    public boolean completeStep(DeletionLease lease, DeletionStep step, Instant completedAt) {
      return mutation("complete:" + step, step != rejectedStep);
    }

    @Override
    public boolean startStep(DeletionLease lease, DeletionStep step, Instant startedAt) {
      return mutation("start:" + step, step != rejectedStartStep);
    }

    @Override
    public boolean completeAuthDeletionAndClearSubject(DeletionLease lease, Instant completedAt) {
      subjectCleared = authCompletionAccepted;
      return mutation("complete-auth-and-clear-subject", authCompletionAccepted);
    }

    @Override
    public boolean succeed(DeletionLease lease, Instant completedAt) {
      succeeded = true;
      return mutation("succeed", true);
    }

    @Override
    public boolean confirmCancelled(DeletionLease lease, Instant completedAt) {
      cancelled = true;
      return mutation("cancel", true);
    }

    @Override
    public boolean retry(
        DeletionLease lease, String failureCode, Instant nextRetryAt, Instant failedAt) {
      this.retryCode = failureCode;
      this.nextRetryAt = nextRetryAt;
      return mutation("retry:" + failureCode, true);
    }

    @Override
    public boolean fail(DeletionLease lease, String failureCode, Instant failedAt) {
      this.failed = true;
      this.failureCode = failureCode;
      return mutation("fail:" + failureCode, true);
    }

    private boolean hasTerminalWrite() {
      return succeeded || failed || cancelled || nextRetryAt != null;
    }

    private <T> T mutation(String event, T result) {
      inMutation = true;
      try {
        events.add(event);
        return result;
      } finally {
        inMutation = false;
      }
    }
  }
}
