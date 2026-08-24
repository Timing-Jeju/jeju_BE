package com.timingjeju.api.global.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.ConsentUpdateResult;
import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalProfileException;
import com.timingjeju.api.application.legal.UserConsentStore;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JdbcLegalConsentStoreIntegrationTest.FixedClockConfiguration.class)
class JdbcLegalConsentStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID USER_ID = UUID.fromString("19000000-0000-0000-0000-000000000101");
  private static final UUID OTHER_USER_ID = UUID.fromString("19000000-0000-0000-0000-000000000102");
  private static final UUID TERMS_ID = UUID.fromString("09200000-0000-0000-0000-000000000001");
  private static final UUID PRIVACY_ID = UUID.fromString("09200000-0000-0000-0000-000000000002");
  private static final UUID LOCATION_ID = UUID.fromString("09200000-0000-0000-0000-000000000003");
  private static final UUID NEW_TERMS_ID = UUID.fromString("19200000-0000-0000-0000-000000000201");
  private static final UUID UNKNOWN_ID = UUID.fromString("19200000-0000-0000-0000-000000000999");
  private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

  @Autowired private UserConsentStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  @AfterEach
  void cleanFixtures() {
    jdbc.update("delete from public.user_consents where user_id in (?, ?)", USER_ID, OTHER_USER_ID);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", USER_ID, OTHER_USER_ID);
    jdbc.update("delete from auth.users where id in (?, ?)", USER_ID, OTHER_USER_ID);
    jdbc.update("delete from public.legal_documents where id = ?", NEW_TERMS_ID);
  }

  @Test
  void 다건_최신필수동의는_한_transaction으로_저장하고_다른사용자_surface는_0이다() {
    insertProfile(USER_ID);
    insertProfile(OTHER_USER_ID);

    ConsentUpdateResult result = store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW);

    assertThat(result.requiredConsentsSatisfied()).isTrue();
    assertThat(result.updatedAt()).isEqualTo(NOW);
    assertThat(consentCount(USER_ID)).isEqualTo(3);
    assertThat(consentCount(OTHER_USER_ID)).isZero();
  }

  @Test
  void unknown이나_old_document가_섞이면_일부동의도_남지않는다() {
    insertProfile(USER_ID);
    assertInvalid(
        () ->
            store.updateRequiredConsents(
                USER_ID,
                "ko-KR",
                List.of(new ConsentDecision(TERMS_ID, true), new ConsentDecision(UNKNOWN_ID, true)),
                NOW));
    assertThat(consentCount(USER_ID)).isZero();

    insertNewTerms();
    assertInvalid(() -> store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW));
    assertThat(consentCount(USER_ID)).isZero();
  }

  @Test
  void 일부필수누락과_필수false는_LEGAL_CONSENT_REQUIRED로_전체rollback한다() {
    insertProfile(USER_ID);

    assertConsentRequired(
        () ->
            store.updateRequiredConsents(
                USER_ID,
                "ko-KR",
                List.of(new ConsentDecision(TERMS_ID, true), new ConsentDecision(PRIVACY_ID, true)),
                NOW));
    assertThat(consentCount(USER_ID)).isZero();

    assertConsentRequired(
        () ->
            store.updateRequiredConsents(
                USER_ID, "ko-KR", List.of(new ConsentDecision(LOCATION_ID, false)), NOW));
    assertThat(consentCount(USER_ID)).isZero();
  }

  @Test
  void 미래시행_version은_latest에서_제외되어_현재문서동의를_방해하지않는다() {
    insertProfile(USER_ID);
    jdbc.update(
        """
        insert into public.legal_documents (
          id, document_type, locale, version, title, content_url, required, effective_at
        ) values (?, 'terms', 'ko-KR', '2.0.0', '미래 이용약관',
                  'https://timing-jeju.example/legal/terms/2.0.0', true, ?)
        """,
        NEW_TERMS_ID,
        Timestamp.from(NOW.plusSeconds(86_400)));

    ConsentUpdateResult result = store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW);

    assertThat(result.requiredConsentsSatisfied()).isTrue();
    assertThat(consentCount(USER_ID)).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.user_consents where legal_document_id = ?",
                Integer.class,
                NEW_TERMS_ID))
        .isZero();
  }

  @Test
  void 동일요청_retry는_행과_agreedAt을_바꾸지않고_새version은_별도동의로_갱신한다() {
    insertProfile(USER_ID);
    store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW);
    Instant firstAgreedAt = agreedAt(TERMS_ID);

    ConsentUpdateResult retry =
        store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW.plusSeconds(60));

    assertThat(consentCount(USER_ID)).isEqualTo(3);
    assertThat(agreedAt(TERMS_ID)).isEqualTo(firstAgreedAt);
    assertThat(retry.updatedAt()).isEqualTo(NOW);

    insertNewTerms();
    List<ConsentDecision> updated =
        List.of(
            new ConsentDecision(NEW_TERMS_ID, true),
            new ConsentDecision(PRIVACY_ID, true),
            new ConsentDecision(LOCATION_ID, true));
    store.updateRequiredConsents(USER_ID, "ko-KR", updated, NOW.plusSeconds(120));
    assertThat(consentCount(USER_ID)).isEqualTo(4);
  }

  @Test
  void 동일sub의_동시_true_false는_늦은평가시각의_false로_단조수렴한다() throws Exception {
    insertProfile(USER_ID);
    store.updateRequiredConsents(USER_ID, "ko-KR", allRequired(), NOW);
    insertOptionalTerms();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> concurrentUpdate(ready, start, true, NOW.plusSeconds(60)));
      var second =
          executor.submit(() -> concurrentUpdate(ready, start, false, NOW.plusSeconds(120)));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(first.get().requiredConsentsSatisfied()).isTrue();
      assertThat(second.get().requiredConsentsSatisfied()).isTrue();
    }
    assertThat(consentCount(USER_ID)).isEqualTo(4);
    ConsentState state = consentState(NEW_TERMS_ID);
    assertThat(state.agreed()).isFalse();
    assertThat(state.agreedAt()).isEqualTo(NOW.plusSeconds(120));
    assertThat(state.withdrawnAt()).isEqualTo(NOW.plusSeconds(120));
  }

  @Test
  void diagnostic_ordered_fail_fast_jdbc_stages() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              try {
                runJdbcStage(
                    JdbcStage.PROFILE_LOCK,
                    () -> {
                      insertProfile(USER_ID);
                      int rows =
                          jdbc.queryForList(
                                  JdbcLegalConsentStore.profileLockSql(), UUID.class, USER_ID)
                              .size();
                      if (rows != 1) {
                        throw new AssertionError("unexpected cardinality");
                      }
                    });
                runJdbcStage(
                    JdbcStage.EFFECTIVE_CANDIDATES,
                    () -> {
                      Timestamp evaluatedAt = Timestamp.from(NOW);
                      List<LegalDocument> documents =
                          jdbc.query(
                              JdbcLegalConsentStore.effectiveCandidatesSql(),
                              (resultSet, rowNumber) -> JdbcLegalConsentStore.document(resultSet),
                              "ko-KR",
                              evaluatedAt,
                              evaluatedAt);
                      boolean validMapping =
                          documents.size() == 3
                              && documents.stream()
                                  .allMatch(
                                      document ->
                                          document.documentId() != null
                                              && document.effectiveAt() != null
                                              && "ko-KR".equals(document.locale()));
                      if (!validMapping) {
                        throw new AssertionError("unexpected mapping cardinality");
                      }
                    });
                runJdbcStage(
                    JdbcStage.CONSENT_UPSERT,
                    () -> {
                      if (rawUpsert(TERMS_ID, NOW) != 1) {
                        throw new AssertionError("unexpected update cardinality");
                      }
                    });
                runJdbcStage(
                    JdbcStage.REQUIRED_COUNT,
                    () -> {
                      seedRemainingRequiredConsents();
                      Integer count =
                          jdbc.queryForObject(
                              JdbcLegalConsentStore.requiredAgreementCountSql(3),
                              Integer.class,
                              USER_ID,
                              TERMS_ID,
                              PRIVACY_ID,
                              LOCATION_ID);
                      if (count == null || count != 3) {
                        throw new AssertionError("unexpected count");
                      }
                    });
                runJdbcStage(
                    JdbcStage.MAX_TIMESTAMP,
                    () -> {
                      Timestamp updatedAt =
                          jdbc.queryForObject(
                              JdbcLegalConsentStore.updatedAtSql(3),
                              Timestamp.class,
                              USER_ID,
                              TERMS_ID,
                              PRIVACY_ID,
                              LOCATION_ID);
                      if (!Timestamp.from(NOW).equals(updatedAt)) {
                        throw new AssertionError("unexpected timestamp");
                      }
                    });
              } finally {
                status.setRollbackOnly();
              }
            });
  }

  private void insertProfile(UUID userId) {
    jdbc.update("insert into auth.users (id, raw_user_meta_data) values (?, '{}'::jsonb)", userId);
    jdbc.update("insert into public.user_profiles (id, locale) values (?, 'ko-KR')", userId);
  }

  private void insertNewTerms() {
    jdbc.update(
        """
        insert into public.legal_documents (
          id, document_type, locale, version, title, content_url, required, effective_at
        ) values (?, 'terms', 'ko-KR', '2.0.0', '새 이용약관',
                  'https://timing-jeju.example/legal/terms/2.0.0', true, ?)
        """,
        NEW_TERMS_ID,
        Timestamp.from(NOW.minusSeconds(1)));
  }

  private void insertOptionalTerms() {
    jdbc.update(
        """
        insert into public.legal_documents (
          id, document_type, locale, version, title, content_url, required, effective_at
        ) values (?, 'terms', 'ko-KR', '2.0.0', '선택 이용약관',
                  'https://timing-jeju.example/legal/terms/2.0.0', false, ?)
        """,
        NEW_TERMS_ID,
        Timestamp.from(NOW.minusSeconds(1)));
  }

  private static List<ConsentDecision> allRequired() {
    return List.of(
        new ConsentDecision(TERMS_ID, true),
        new ConsentDecision(PRIVACY_ID, true),
        new ConsentDecision(LOCATION_ID, true));
  }

  private void seedRemainingRequiredConsents() {
    if (rawUpsert(PRIVACY_ID, NOW) != 1 || rawUpsert(LOCATION_ID, NOW) != 1) {
      throw new AssertionError("unexpected seed cardinality");
    }
  }

  private int rawUpsert(UUID documentId, Instant evaluatedAt) {
    Timestamp timestamp = Timestamp.from(evaluatedAt);
    return jdbc.update(
        JdbcLegalConsentStore.upsertConsentSql(),
        USER_ID,
        documentId,
        true,
        timestamp,
        true,
        timestamp);
  }

  private static void runJdbcStage(JdbcStage stage, Runnable assertion) {
    try {
      assertion.run();
    } catch (AssertionError | RuntimeException failure) {
      throw stableStageFailure(stage, failure);
    }
  }

  private static AssertionError stableStageFailure(JdbcStage stage, Throwable failure) {
    Throwable current = failure;
    String sqlState = "NONE";
    while (current != null) {
      if (current instanceof java.sql.SQLException sqlException) {
        sqlState = sqlException.getSQLState() == null ? "NONE" : sqlException.getSQLState();
        break;
      }
      current = current.getCause();
    }
    return new AssertionError(
        "JDBC_STAGE_FAILED:%s:%s:%s"
            .formatted(stage, failure.getClass().getSimpleName(), sqlState));
  }

  private enum JdbcStage {
    PROFILE_LOCK,
    EFFECTIVE_CANDIDATES,
    CONSENT_UPSERT,
    REQUIRED_COUNT,
    MAX_TIMESTAMP
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    @Bean
    @Primary
    Clock issue19FixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  private int consentCount(UUID userId) {
    return jdbc.queryForObject(
        "select count(*) from public.user_consents where user_id = ?", Integer.class, userId);
  }

  private Instant agreedAt(UUID documentId) {
    return jdbc.queryForObject(
            "select agreed_at from public.user_consents where user_id = ? and legal_document_id = ?",
            java.sql.Timestamp.class,
            USER_ID,
            documentId)
        .toInstant();
  }

  private ConsentUpdateResult concurrentUpdate(
      CountDownLatch ready, CountDownLatch start, boolean agreed, Instant evaluatedAt)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("동시 시작 barrier timeout");
    }
    return store.updateRequiredConsents(
        USER_ID, "ko-KR", List.of(new ConsentDecision(NEW_TERMS_ID, agreed)), evaluatedAt);
  }

  private ConsentState consentState(UUID documentId) {
    return jdbc.queryForObject(
        """
        select agreed, agreed_at, withdrawn_at
        from public.user_consents
        where user_id = ? and legal_document_id = ?
        """,
        (resultSet, rowNumber) ->
            new ConsentState(
                resultSet.getBoolean("agreed"),
                resultSet.getTimestamp("agreed_at").toInstant(),
                resultSet.getTimestamp("withdrawn_at").toInstant()),
        USER_ID,
        documentId);
  }

  private record ConsentState(boolean agreed, Instant agreedAt, Instant withdrawnAt) {}

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    LegalProfileException failure = catchThrowableOfType(LegalProfileException.class, call);
    assertThat(failure.code()).isEqualTo("INVALID_PROFILE_LEGAL_REQUEST");
    assertThat(failure).hasNoCause().hasMessage(null);
  }

  private static void assertConsentRequired(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    LegalProfileException failure = catchThrowableOfType(LegalProfileException.class, call);
    assertThat(failure.code()).isEqualTo("LEGAL_CONSENT_REQUIRED");
    assertThat(failure).hasNoCause().hasMessage(null);
  }
}
