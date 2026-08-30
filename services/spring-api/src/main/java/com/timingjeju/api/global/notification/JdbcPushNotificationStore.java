package com.timingjeju.api.global.notification;

import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalDocumentSelection;
import com.timingjeju.api.application.notification.NotificationPreference;
import com.timingjeju.api.application.notification.NotificationPreferenceStore;
import com.timingjeju.api.application.notification.NotificationPreferenceUpdate;
import com.timingjeju.api.application.notification.ProtectedPushDeviceRegistration;
import com.timingjeju.api.application.notification.PushDevice;
import com.timingjeju.api.application.notification.PushDeviceStore;
import com.timingjeju.api.application.notification.PushEligibilityStore;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.notification.PushNotificationWithdrawalBoundary;
import com.timingjeju.api.application.notification.PushPermissionStatus;
import com.timingjeju.api.application.notification.PushPlatform;
import com.timingjeju.api.application.notification.StoredEligiblePushDevice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPushNotificationStore
    implements PushDeviceStore,
        NotificationPreferenceStore,
        PushEligibilityStore,
        PushNotificationWithdrawalBoundary {

  private static final String LOCK_TOKEN_SQL =
      "select pg_advisory_xact_lock(hashtextextended(encode(?::bytea, 'hex'), 113))";
  private static final String INVALIDATE_MOVED_TOKEN_SQL =
      """
      update public.push_devices
      set invalidated_at = ?::timestamptz, updated_at = ?::timestamptz
      where token_fingerprint = ?::bytea
        and invalidated_at is null
        and (user_id, device_id) <> (?::uuid, ?::uuid)
      """;
  private static final String UPSERT_DEVICE_SQL =
      """
      insert into public.push_devices (
        user_id, device_id, platform, token_ciphertext, token_fingerprint,
        permission_status, app_version, locale, time_zone,
        last_seen_at, invalidated_at, created_at, updated_at
      ) values (
        ?::uuid, ?::uuid, ?, ?, ?::bytea, ?, ?, ?, ?,
        ?::timestamptz,
        case when ? = 'GRANTED' then null else ?::timestamptz end,
        ?::timestamptz, ?::timestamptz
      )
      on conflict (user_id, device_id) do update set
        platform = excluded.platform,
        token_ciphertext = excluded.token_ciphertext,
        token_fingerprint = excluded.token_fingerprint,
        permission_status = excluded.permission_status,
        app_version = excluded.app_version,
        locale = excluded.locale,
        time_zone = excluded.time_zone,
        last_seen_at = excluded.last_seen_at,
        invalidated_at = excluded.invalidated_at,
        updated_at = excluded.updated_at
      returning device_id, platform, permission_status, invalidated_at, updated_at
      """;
  private static final String INVALIDATE_DEVICE_SQL =
      """
      update public.push_devices
      set invalidated_at = coalesce(invalidated_at, ?::timestamptz),
          updated_at = case when invalidated_at is null then ?::timestamptz else updated_at end
      where user_id = ?::uuid and device_id = ?::uuid
      """;
  private static final String INVALIDATE_WITHDRAWING_USER_SQL =
      """
      update public.push_devices
      set invalidated_at = coalesce(invalidated_at, ?::timestamptz),
          updated_at = case when invalidated_at is null then ?::timestamptz else updated_at end
      where user_id = ?::uuid
      """;
  private static final String FIND_PREFERENCE_SQL =
      """
      select next_destination_departure_enabled, safety_buffer_minutes, updated_at
      from public.notification_preferences where user_id = ?::uuid
      """;
  private static final String UPSERT_PREFERENCE_SQL =
      """
      insert into public.notification_preferences (
        user_id, next_destination_departure_enabled, safety_buffer_minutes, created_at, updated_at
      ) values (?::uuid, ?::boolean, ?::integer, ?::timestamptz, ?::timestamptz)
      on conflict (user_id) do update set
        next_destination_departure_enabled = excluded.next_destination_departure_enabled,
        safety_buffer_minutes = excluded.safety_buffer_minutes,
        updated_at = excluded.updated_at
      returning next_destination_departure_enabled, safety_buffer_minutes, updated_at
      """;
  private static final String ACTIVE_PROFILE_LOCALE_SQL =
      "select locale from public.user_profiles where id = ?::uuid and status = 'active'";
  private static final String EFFECTIVE_LOCATION_CANDIDATES_SQL =
      """
      select id, document_type, locale, version, title, content_url, required, effective_at
      from public.legal_documents
      where document_type = 'location'
        and required = true
        and locale in (?, 'ko-KR')
        and effective_at <= ?::timestamptz
        and (retired_at is null or retired_at > ?::timestamptz)
      order by id
      """;
  private static final String ELIGIBLE_FOR_DOCUMENT_SQL =
      """
      select d.device_id, d.platform, d.token_ciphertext, p.safety_buffer_minutes,
             consent_document.id as location_document_id,
             consent_document.version as location_document_version
      from public.push_devices d
      join public.notification_preferences p on p.user_id = d.user_id
      join public.legal_documents consent_document on consent_document.id = ?::uuid
      join public.user_consents consent
        on consent.user_id = d.user_id
       and consent.legal_document_id = consent_document.id
       and consent.agreed = true
       and consent.withdrawn_at is null
       and consent.agreed_at <= ?::timestamptz
      where d.user_id = ?::uuid
        and d.invalidated_at is null
        and d.permission_status = 'GRANTED'
        and p.next_destination_departure_enabled = true
      order by d.device_id
      """;

  private final JdbcTemplate jdbc;

  public JdbcPushNotificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public PushDevice register(
      UUID userId,
      UUID deviceId,
      ProtectedPushDeviceRegistration registration,
      Instant observedAt) {
    try {
      byte[] fingerprint = registration.tokenFingerprint();
      jdbc.queryForObject(LOCK_TOKEN_SQL, (resultSet, rowNumber) -> Boolean.TRUE, fingerprint);
      Timestamp timestamp = Timestamp.from(observedAt);
      jdbc.update(INVALIDATE_MOVED_TOKEN_SQL, timestamp, timestamp, fingerprint, userId, deviceId);
      return jdbc.queryForObject(
          UPSERT_DEVICE_SQL,
          JdbcPushNotificationStore::device,
          userId,
          deviceId,
          registration.platform().name(),
          registration.tokenCiphertext(),
          fingerprint,
          registration.permissionStatus().name(),
          registration.appVersion(),
          registration.locale(),
          registration.timeZone(),
          timestamp,
          registration.permissionStatus().name(),
          timestamp,
          timestamp,
          timestamp);
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public void invalidate(UUID userId, UUID deviceId, Instant invalidatedAt) {
    try {
      Timestamp timestamp = Timestamp.from(invalidatedAt);
      jdbc.update(INVALIDATE_DEVICE_SQL, timestamp, timestamp, userId, deviceId);
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public void onWithdrawalRequested(UUID userId, Instant requestedAt) {
    try {
      Timestamp timestamp = Timestamp.from(requestedAt);
      jdbc.update(INVALIDATE_WITHDRAWING_USER_SQL, timestamp, timestamp, userId);
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<NotificationPreference> find(UUID userId) {
    try {
      return jdbc.query(FIND_PREFERENCE_SQL, JdbcPushNotificationStore::preference, userId).stream()
          .findFirst();
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public NotificationPreference save(
      UUID userId, NotificationPreferenceUpdate update, Instant updatedAt) {
    try {
      Timestamp timestamp = Timestamp.from(updatedAt);
      return jdbc.queryForObject(
          UPSERT_PREFERENCE_SQL,
          JdbcPushNotificationStore::preference,
          userId,
          update.nextDestinationDepartureEnabled(),
          update.safetyBufferMinutes(),
          timestamp,
          timestamp);
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<StoredEligiblePushDevice> findEligible(UUID userId, Instant evaluatedAt) {
    try {
      Timestamp timestamp = Timestamp.from(evaluatedAt);
      Optional<String> locale =
          jdbc
              .query(ACTIVE_PROFILE_LOCALE_SQL, (resultSet, row) -> resultSet.getString(1), userId)
              .stream()
              .findFirst();
      if (locale.isEmpty()) {
        return List.of();
      }
      List<LegalDocument> candidates =
          jdbc.query(
              EFFECTIVE_LOCATION_CANDIDATES_SQL,
              JdbcPushNotificationStore::legalDocument,
              locale.get(),
              timestamp,
              timestamp);
      Optional<LegalDocument> selected =
          LegalDocumentSelection.latest(candidates, locale.get()).stream().findFirst();
      if (selected.isEmpty()) {
        return List.of();
      }
      return jdbc.query(
          ELIGIBLE_FOR_DOCUMENT_SQL,
          (resultSet, rowNumber) ->
              new StoredEligiblePushDevice(
                  resultSet.getObject("device_id", UUID.class),
                  PushPlatform.valueOf(resultSet.getString("platform")),
                  resultSet.getString("token_ciphertext"),
                  resultSet.getInt("safety_buffer_minutes"),
                  resultSet.getObject("location_document_id", UUID.class),
                  resultSet.getString("location_document_version")),
          selected.get().documentId(),
          timestamp,
          userId);
    } catch (DataAccessException failure) {
      throw PushNotificationException.dataUnavailable();
    }
  }

  private static LegalDocument legalDocument(ResultSet resultSet, int row) throws SQLException {
    return new LegalDocument(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("document_type"),
        resultSet.getString("locale"),
        resultSet.getString("version"),
        resultSet.getString("title"),
        resultSet.getString("content_url"),
        resultSet.getBoolean("required"),
        resultSet.getTimestamp("effective_at").toInstant());
  }

  private static PushDevice device(ResultSet resultSet, int row) throws SQLException {
    return new PushDevice(
        resultSet.getObject("device_id", UUID.class),
        PushPlatform.valueOf(resultSet.getString("platform")),
        PushPermissionStatus.valueOf(resultSet.getString("permission_status")),
        resultSet.getTimestamp("invalidated_at") == null,
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static NotificationPreference preference(ResultSet resultSet, int row)
      throws SQLException {
    return new NotificationPreference(
        resultSet.getBoolean("next_destination_departure_enabled"),
        resultSet.getInt("safety_buffer_minutes"),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}
