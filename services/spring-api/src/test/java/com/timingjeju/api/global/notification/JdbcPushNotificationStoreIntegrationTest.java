package com.timingjeju.api.global.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalDocumentSelection;
import com.timingjeju.api.application.notification.NotificationPreferenceStore;
import com.timingjeju.api.application.notification.NotificationPreferenceUpdate;
import com.timingjeju.api.application.notification.ProtectedPushDeviceRegistration;
import com.timingjeju.api.application.notification.PushDeviceStore;
import com.timingjeju.api.application.notification.PushEligibilityStore;
import com.timingjeju.api.application.notification.PushNotificationWithdrawalBoundary;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import com.timingjeju.api.application.notification.RegistrationTokenProtector;
import com.timingjeju.api.support.postgresql.PostgreSqlRepositoryIntegrationTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcPushNotificationStoreIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {

  private static final UUID USER = UUID.fromString("11300000-0000-0000-0000-000000000001");
  private static final UUID OTHER = UUID.fromString("11300000-0000-0000-0000-000000000002");
  private static final UUID DEVICE = UUID.fromString("11300000-0000-0000-0000-000000000101");
  private static final UUID OTHER_DEVICE = UUID.fromString("11300000-0000-0000-0000-000000000102");
  private static final UUID LOCATION_DOCUMENT =
      UUID.fromString("09200000-0000-0000-0000-000000000003");
  private static final UUID NEW_LOCATION_DOCUMENT =
      UUID.fromString("11300000-0000-0000-0000-000000000201");
  private static final UUID EN_LOCATION_2026_9 =
      UUID.fromString("11300000-0000-0000-0000-000000000211");
  private static final UUID EN_LOCATION_2026_10_ASC =
      UUID.fromString("11300000-0000-0000-0000-000000000212");
  private static final UUID EN_LOCATION_2026_10_DESC =
      UUID.fromString("11300000-0000-0000-0000-000000000213");
  private static final Instant NOW = Instant.parse("2026-09-02T01:00:00Z");

  @Autowired private PushDeviceStore devices;
  @Autowired private NotificationPreferenceStore preferences;
  @Autowired private PushEligibilityStore eligibility;
  @Autowired private PushNotificationWithdrawalBoundary withdrawalBoundary;
  @Autowired private RegistrationTokenProtector tokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("delete from public.push_devices where user_id in (?, ?)", USER, OTHER);
    jdbc.update("delete from public.notification_preferences where user_id in (?, ?)", USER, OTHER);
    jdbc.update("delete from public.user_consents where user_id in (?, ?)", USER, OTHER);
    jdbc.update("delete from public.user_profiles where id in (?, ?)", USER, OTHER);
    jdbc.update("delete from auth.users where id in (?, ?)", USER, OTHER);
    jdbc.update("delete from public.legal_documents where id = ?", NEW_LOCATION_DOCUMENT);
    jdbc.update(
        "delete from public.legal_documents where id in (?, ?, ?)",
        EN_LOCATION_2026_9,
        EN_LOCATION_2026_10_ASC,
        EN_LOCATION_2026_10_DESC);
  }

  @Test
  void 동일기기_upsert와_token회전은_원문없이_한행만_갱신한다() {
    insertUser(USER);
    devices.register(USER, DEVICE, registration("__REDACTED_FIRST_TOKEN__"), NOW);

    var rotated =
        devices.register(
            USER, DEVICE, registration("__REDACTED_ROTATED_TOKEN__"), NOW.plusSeconds(1));

    assertThat(rotated.active()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.push_devices where user_id = ?", Integer.class, USER))
        .isOne();
    String ciphertext =
        jdbc.queryForObject(
            "select token_ciphertext from public.push_devices where user_id = ?",
            String.class,
            USER);
    assertThat(ciphertext)
        .doesNotContain("__REDACTED_FIRST_TOKEN__")
        .doesNotContain("__REDACTED_ROTATED_TOKEN__");
    assertThat(tokens.reveal(ciphertext)).isEqualTo("__REDACTED_ROTATED_TOKEN__");
  }

  @Test
  void 같은_token의_동시_다른사용자등록은_정확히_한_active소유자로_수렴한다() throws Exception {
    insertUser(USER);
    insertUser(OTHER);
    var first = registration("__REDACTED_SHARED_TOKEN__");
    var second = registration("__REDACTED_SHARED_TOKEN__");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var one = executor.submit(() -> registerConcurrently(ready, start, USER, DEVICE, first));
      var two =
          executor.submit(() -> registerConcurrently(ready, start, OTHER, OTHER_DEVICE, second));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      one.get();
      two.get();
    }

    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.push_devices where token_fingerprint = ? and invalidated_at is null",
                Integer.class,
                first.tokenFingerprint()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.push_devices where token_fingerprint = ?",
                Integer.class,
                first.tokenFingerprint()))
        .isEqualTo(2);
  }

  @Test
  void locale_CHECK는_canonical_BCP47확장과_35자를_허용하고_case_36자_invalid를_거부한다() {
    insertUser(USER);
    devices.register(
        USER, DEVICE, registration("token-extension-locale", "en-US-u-ca-gregory"), NOW);
    devices.register(
        USER,
        DEVICE,
        registration("token-max-locale", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-ddd"),
        NOW.plusSeconds(1));

    for (String invalid : new String[] {"en-us", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-dddd"}) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "update public.push_devices set locale = ? where user_id = ? and device_id = ?",
                      invalid,
                      USER,
                      DEVICE))
          .hasRootCauseInstanceOf(SQLException.class)
          .rootCause()
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
    }
  }

  @Test
  void 해제는_자기기기만_비활성화하고_반복호출과_missing도_성공한다() {
    insertUser(USER);
    insertUser(OTHER);
    devices.register(USER, DEVICE, registration("__REDACTED_OWNER_TOKEN__"), NOW);
    devices.register(OTHER, OTHER_DEVICE, registration("__REDACTED_OTHER_TOKEN__"), NOW);

    devices.invalidate(USER, DEVICE, NOW.plusSeconds(1));
    devices.invalidate(USER, DEVICE, NOW.plusSeconds(2));
    devices.invalidate(USER, OTHER_DEVICE, NOW.plusSeconds(2));

    assertThat(active(USER, DEVICE)).isFalse();
    assertThat(active(OTHER, OTHER_DEVICE)).isTrue();
  }

  @Test
  void eligibility는_optIn_OS권한_최신required위치동의를_모두_재검사한다() {
    insertUser(USER);
    insertProfile(USER);
    devices.register(USER, DEVICE, registration("__REDACTED_ELIGIBLE_TOKEN__"), NOW);
    preferences.save(USER, new NotificationPreferenceUpdate(true, 10), NOW);

    assertThat(eligibility.findEligible(USER, NOW)).isEmpty();
    consent(USER, LOCATION_DOCUMENT, true, null);
    assertThat(eligibility.findEligible(USER, NOW))
        .singleElement()
        .satisfies(
            result -> {
              assertThat(result.deviceId()).isEqualTo(DEVICE);
              assertThat(result.locationConsentDocumentId()).isEqualTo(LOCATION_DOCUMENT);
              assertThat(result.locationConsentVersion()).isEqualTo("2026-08-11.v1");
            });

    preferences.save(USER, new NotificationPreferenceUpdate(false, 10), NOW.plusSeconds(1));
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(1))).isEmpty();
    preferences.save(USER, new NotificationPreferenceUpdate(true, 10), NOW.plusSeconds(2));
    devices.register(
        USER, DEVICE, deniedRegistration("__REDACTED_ELIGIBLE_TOKEN__"), NOW.plusSeconds(2));
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(2))).isEmpty();
    devices.register(USER, DEVICE, registration("__REDACTED_ELIGIBLE_TOKEN__"), NOW.plusSeconds(3));

    insertNewLocationDocument();
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(3))).isEmpty();
    consent(USER, NEW_LOCATION_DOCUMENT, true, null);
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(3)))
        .singleElement()
        .satisfies(result -> assertThat(result.locationConsentVersion()).isEqualTo("2026-09.v2"));

    consent(USER, NEW_LOCATION_DOCUMENT, false, NOW.plusSeconds(4));
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(4))).isEmpty();
  }

  @Test
  void eligibility법정문서는_요청locale우선_koFallback_semver_documentIdASC와_철회를_정렬한다() {
    insertUser(USER);
    insertProfile(USER, "en-US");
    devices.register(USER, DEVICE, registration("token-locale-selection"), NOW);
    preferences.save(USER, new NotificationPreferenceUpdate(true, 10), NOW);
    insertLocationDocument(EN_LOCATION_2026_9, "en-US", "2026-9.v9", NOW.minusSeconds(1));
    insertLocationDocument(EN_LOCATION_2026_10_DESC, "en-US", "2026-10.1", NOW);
    insertLocationDocument(EN_LOCATION_2026_10_ASC, "en-US", "2026-10.v1", NOW);

    consent(USER, LOCATION_DOCUMENT, true, null);
    consent(USER, EN_LOCATION_2026_9, true, null);
    consent(USER, EN_LOCATION_2026_10_DESC, true, null);
    assertThat(eligibility.findEligible(USER, NOW)).isEmpty();

    consent(USER, EN_LOCATION_2026_10_ASC, true, null);
    assertThat(eligibility.findEligible(USER, NOW))
        .singleElement()
        .satisfies(
            device -> {
              assertThat(device.locationConsentDocumentId()).isEqualTo(EN_LOCATION_2026_10_ASC);
              assertThat(device.locationConsentVersion()).isEqualTo("2026-10.v1");
            });
    consent(USER, EN_LOCATION_2026_10_ASC, false, NOW.plusSeconds(1));
    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(1))).isEmpty();

    insertUser(OTHER);
    insertProfile(OTHER, "fr-FR");
    devices.register(OTHER, OTHER_DEVICE, registration("token-fallback"), NOW);
    preferences.save(OTHER, new NotificationPreferenceUpdate(true, 10), NOW);
    consent(OTHER, LOCATION_DOCUMENT, true, null);
    assertThat(eligibility.findEligible(OTHER, NOW))
        .singleElement()
        .satisfies(
            device -> assertThat(device.locationConsentDocumentId()).isEqualTo(LOCATION_DOCUMENT));
  }

  @Test
  void eligibility_version선택은_issue19_Java_semantics와_exact일치한다() {
    insertUser(USER);
    insertProfile(USER, "en-US");
    devices.register(USER, DEVICE, registration("token-version-parity"), NOW);
    preferences.save(USER, new NotificationPreferenceUpdate(true, 10), NOW);

    for (List<String> versions :
        List.of(
            List.of("1.0.0", "1.0"),
            List.of("1.0-alpha", "1.0-beta"),
            List.of("1.0.999999999999999999999999999999", "1.0.2"),
            List.of("v1.0", "1.0"))) {
      jdbc.update("delete from public.user_consents where user_id = ?", USER);
      jdbc.update(
          "delete from public.legal_documents where id in (?, ?)",
          EN_LOCATION_2026_10_ASC,
          EN_LOCATION_2026_10_DESC);
      insertLocationDocument(EN_LOCATION_2026_10_ASC, "en-US", versions.get(0), NOW);
      insertLocationDocument(EN_LOCATION_2026_10_DESC, "en-US", versions.get(1), NOW);

      List<LegalDocument> candidates =
          List.of(
              legalDocument(EN_LOCATION_2026_10_ASC, versions.get(0)),
              legalDocument(EN_LOCATION_2026_10_DESC, versions.get(1)));
      LegalDocument winner = LegalDocumentSelection.latest(candidates, "en-US").get(0);
      UUID loser =
          winner.documentId().equals(EN_LOCATION_2026_10_ASC)
              ? EN_LOCATION_2026_10_DESC
              : EN_LOCATION_2026_10_ASC;

      consent(USER, loser, true, null);
      assertThat(eligibility.findEligible(USER, NOW)).isEmpty();
      consent(USER, winner.documentId(), true, null);
      assertThat(eligibility.findEligible(USER, NOW))
          .singleElement()
          .satisfies(
              device -> {
                assertThat(device.locationConsentDocumentId()).isEqualTo(winner.documentId());
                assertThat(device.locationConsentVersion()).isEqualTo(winner.version());
              });
      consent(USER, winner.documentId(), false, NOW);
      assertThat(eligibility.findEligible(USER, NOW)).isEmpty();
    }
  }

  @Test
  void eligibility는_호출시작_snapshot을_유지하고_동시_최신required문서는_다음호출에_반영한다() throws Exception {
    insertUser(USER);
    insertProfile(USER);
    devices.register(USER, DEVICE, registration("token-repeatable-read"), NOW);
    preferences.save(USER, new NotificationPreferenceUpdate(true, 10), NOW);
    consent(USER, LOCATION_DOCUMENT, true, null);

    try (Connection writer = dataSource.getConnection();
        var executor = Executors.newSingleThreadExecutor()) {
      writer.setAutoCommit(false);
      try (var lock = writer.createStatement()) {
        lock.execute("lock table public.legal_documents in access exclusive mode");
      }
      var currentInvocation = executor.submit(() -> eligibility.findEligible(USER, NOW));
      awaitLegalDocumentCandidateReadBlocked();
      insertNewLocationDocument(writer);
      writer.commit();

      assertThat(currentInvocation.get(5, TimeUnit.SECONDS))
          .singleElement()
          .satisfies(
              device ->
                  assertThat(device.locationConsentDocumentId()).isEqualTo(LOCATION_DOCUMENT));
    }

    assertThat(eligibility.findEligible(USER, NOW)).isEmpty();
  }

  @Test
  void 탈퇴접수는_즉시_자기모든기기의_eligibility를_0으로_만들고_타사용자에_영향없다() {
    for (UUID userId : new UUID[] {USER, OTHER}) {
      insertUser(userId);
      insertProfile(userId);
      UUID deviceId = userId.equals(USER) ? DEVICE : OTHER_DEVICE;
      devices.register(userId, deviceId, registration("token-" + userId), NOW);
      preferences.save(userId, new NotificationPreferenceUpdate(true, 10), NOW);
      consent(userId, LOCATION_DOCUMENT, true, null);
      assertThat(eligibility.findEligible(userId, NOW)).hasSize(1);
    }

    withdrawalBoundary.onWithdrawalRequested(USER, NOW.plusSeconds(1));

    assertThat(eligibility.findEligible(USER, NOW.plusSeconds(1))).isEmpty();
    assertThat(eligibility.findEligible(OTHER, NOW.plusSeconds(1))).hasSize(1);
    assertThat(active(OTHER, OTHER_DEVICE)).isTrue();
  }

  @Test
  void 최종_auth삭제는_push기기와_preferences를_cascade하고_타사용자를_보존한다() {
    for (UUID userId : new UUID[] {USER, OTHER}) {
      insertUser(userId);
      UUID deviceId = userId.equals(USER) ? DEVICE : OTHER_DEVICE;
      devices.register(userId, deviceId, registration("token-" + userId), NOW);
      preferences.save(userId, new NotificationPreferenceUpdate(true, 10), NOW);
    }

    jdbc.update("delete from auth.users where id = ?", USER);

    assertThat(pushDeviceCount(USER)).isZero();
    assertThat(preferenceCount(USER)).isZero();
    assertThat(pushDeviceCount(OTHER)).isOne();
    assertThat(preferenceCount(OTHER)).isOne();
  }

  @Test
  void authenticated_RLS는_owner_safe열만_보이고_타사용자와_token열을_차단한다() throws Exception {
    insertUser(USER);
    insertUser(OTHER);
    devices.register(USER, DEVICE, registration("__REDACTED_RLS_OWNER_TOKEN__"), NOW);
    devices.register(OTHER, OTHER_DEVICE, registration("__REDACTED_RLS_OTHER_TOKEN__"), NOW);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("set local role authenticated");
        statement.execute("select set_config('request.jwt.claim.sub', '" + USER + "', true)");
        try (var rows =
            statement.executeQuery(
                "select device_id from public.push_devices order by device_id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getObject(1, UUID.class)).isEqualTo(DEVICE);
          assertThat(rows.next()).isFalse();
        }
        assertThatThrownBy(
                () -> statement.executeQuery("select token_ciphertext from public.push_devices"))
            .isInstanceOf(SQLException.class)
            .satisfies(
                error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
      } finally {
        connection.rollback();
      }
    }
  }

  @Test
  void authenticated는_owner_safe열만_읽고_service_role만_두테이블을_쓴다() throws Exception {
    insertUser(USER);
    insertUser(OTHER);
    devices.register(USER, DEVICE, registration("__REDACTED_SERVER_WRITER_TOKEN__"), NOW);
    devices.register(OTHER, OTHER_DEVICE, registration("__REDACTED_OTHER_WRITER_TOKEN__"), NOW);
    preferences.save(USER, new NotificationPreferenceUpdate(true, 17), NOW);

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from pg_catalog.pg_policies
                where schemaname = 'public'
                  and tablename in ('push_devices', 'notification_preferences')
                  and cmd <> 'SELECT'
                """,
                Integer.class))
        .isZero();
    for (String table : List.of("push_devices", "notification_preferences")) {
      for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_table_privilege('authenticated', ?, ?)",
                    Boolean.class,
                    "public." + table,
                    privilege))
            .as("authenticated %s on %s", privilege, table)
            .isFalse();
      }
      for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
        assertThat(
                jdbc.queryForObject(
                    "select has_table_privilege('service_role', ?, ?)",
                    Boolean.class,
                    "public." + table,
                    privilege))
            .as("service_role %s on %s", privilege, table)
            .isTrue();
      }
    }

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("set local role authenticated");
        statement.execute("select set_config('request.jwt.claim.sub', '" + USER + "', true)");
        try (var deviceRows = statement.executeQuery("select device_id from public.push_devices")) {
          assertThat(deviceRows.next()).isTrue();
          assertThat(deviceRows.getObject(1, UUID.class)).isEqualTo(DEVICE);
          assertThat(deviceRows.next()).isFalse();
        }
        try (var preferenceRows =
            statement.executeQuery(
                "select safety_buffer_minutes from public.notification_preferences")) {
          assertThat(preferenceRows.next()).isTrue();
          assertThat(preferenceRows.getInt(1)).isEqualTo(17);
          assertThat(preferenceRows.next()).isFalse();
        }
      } finally {
        connection.rollback();
      }
    }
  }

  private void registerConcurrently(
      CountDownLatch ready,
      CountDownLatch start,
      UUID userId,
      UUID deviceId,
      ProtectedPushDeviceRegistration registration) {
    ready.countDown();
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("concurrent registration start timeout");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("concurrent registration interrupted");
    }
    devices.register(userId, deviceId, registration, NOW);
  }

  private ProtectedPushDeviceRegistration registration(String token) {
    return registration(token, "ko-KR");
  }

  private ProtectedPushDeviceRegistration registration(String token, String locale) {
    var protectedToken = tokens.protect(token);
    return new ProtectedPushDeviceRegistration(
        PushPlatform.IOS,
        protectedToken.ciphertext(),
        protectedToken.fingerprint(),
        PushPermissionStatus.GRANTED,
        "1.2.3",
        locale,
        "Asia/Seoul");
  }

  private ProtectedPushDeviceRegistration deniedRegistration(String token) {
    var protectedToken = tokens.protect(token);
    return new ProtectedPushDeviceRegistration(
        PushPlatform.IOS,
        protectedToken.ciphertext(),
        protectedToken.fingerprint(),
        PushPermissionStatus.DENIED,
        "1.2.3",
        "ko-KR",
        "Asia/Seoul");
  }

  private void insertNewLocationDocument() {
    jdbc.update(
        """
        insert into public.legal_documents (
          id, document_type, locale, version, title, content_url, required, effective_at
        ) values (?, 'location', 'ko-KR', '2026-09.v2', '새 위치 약관',
                  'https://timing-jeju.example/legal/location/2026-09.v2', true, ?)
        """,
        NEW_LOCATION_DOCUMENT,
        java.sql.Timestamp.from(NOW.plusSeconds(3)));
  }

  private void insertNewLocationDocument(Connection connection) throws SQLException {
    try (var insert =
        connection.prepareStatement(
            """
            insert into public.legal_documents (
              id, document_type, locale, version, title, content_url, required, effective_at
            ) values (?, 'location', 'ko-KR', '2026-09.v2', '새 위치 약관',
                      'https://timing-jeju.example/legal/location/2026-09.v2', true, ?)
            """)) {
      insert.setObject(1, NEW_LOCATION_DOCUMENT);
      insert.setTimestamp(2, java.sql.Timestamp.from(NOW));
      insert.executeUpdate();
    }
  }

  private void awaitLegalDocumentCandidateReadBlocked() throws InterruptedException {
    for (int attempt = 0; attempt < 50; attempt++) {
      int blocked =
          jdbc.queryForObject(
              """
              select count(*)
              from pg_stat_activity
              where datname = current_database()
                and pid <> pg_backend_pid()
                and wait_event_type = 'Lock'
                and query like '%from public.legal_documents%'
              """,
              Integer.class);
      if (blocked > 0) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("legal-document candidate read did not block");
  }

  private void insertUser(UUID userId) {
    jdbc.update(
        "insert into auth.users (id, email) values (?, ?)", userId, userId + "@issue113.test");
  }

  private void insertProfile(UUID userId) {
    insertProfile(userId, "ko-KR");
  }

  private void insertProfile(UUID userId, String locale) {
    jdbc.update("insert into public.user_profiles (id, locale) values (?, ?)", userId, locale);
  }

  private void insertLocationDocument(
      UUID documentId, String locale, String version, Instant effectiveAt) {
    jdbc.update(
        """
        insert into public.legal_documents (
          id, document_type, locale, version, title, content_url, required, effective_at
        ) values (?, 'location', ?, ?, 'location consent',
                  'https://timing-jeju.example/legal/location/test', true, ?)
        """,
        documentId,
        locale,
        version,
        java.sql.Timestamp.from(effectiveAt));
  }

  private static LegalDocument legalDocument(UUID documentId, String version) {
    return new LegalDocument(
        documentId,
        "location",
        "en-US",
        version,
        "location consent",
        "https://timing-jeju.example/legal/location/test",
        true,
        NOW);
  }

  private void consent(UUID userId, UUID documentId, boolean agreed, Instant withdrawnAt) {
    jdbc.update(
        """
        insert into public.user_consents (
          user_id, legal_document_id, agreed, agreed_at, withdrawn_at, source
        ) values (?, ?, ?, ?, ?, 'web')
        on conflict (user_id, legal_document_id) do update set
          agreed = excluded.agreed, agreed_at = excluded.agreed_at,
          withdrawn_at = excluded.withdrawn_at
        """,
        userId,
        documentId,
        agreed,
        java.sql.Timestamp.from(NOW),
        withdrawnAt == null ? null : java.sql.Timestamp.from(withdrawnAt));
  }

  private boolean active(UUID userId, UUID deviceId) {
    return jdbc.queryForObject(
        "select invalidated_at is null from public.push_devices where user_id = ? and device_id = ?",
        Boolean.class,
        userId,
        deviceId);
  }

  private int pushDeviceCount(UUID userId) {
    return jdbc.queryForObject(
        "select count(*) from public.push_devices where user_id = ?", Integer.class, userId);
  }

  private int preferenceCount(UUID userId) {
    return jdbc.queryForObject(
        "select count(*) from public.notification_preferences where user_id = ?",
        Integer.class,
        userId);
  }
}
