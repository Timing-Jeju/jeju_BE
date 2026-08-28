package com.timingjeju.api.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.notification.service.PushDeviceService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PushDeviceServiceTest {

  private static final UUID USER_ID = UUID.fromString("11300000-0000-0000-0000-000000000001");
  private static final UUID DEVICE_ID = UUID.fromString("11300000-0000-0000-0000-000000000101");
  private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");

  @Mock private PushDeviceStore store;
  @Mock private RegistrationTokenProtector tokens;

  @Test
  void 등록은_canonical_sub와_암호문_fingerprint만_store에_전달한다() {
    byte[] fingerprint = new byte[] {1, 2, 3};
    when(tokens.protect("__REDACTED_REGISTRATION_TOKEN__"))
        .thenReturn(new ProtectedRegistrationToken("v1.ciphertext", fingerprint));
    when(store.register(eq(USER_ID), eq(DEVICE_ID), any(), eq(NOW)))
        .thenReturn(
            new PushDevice(DEVICE_ID, PushPlatform.IOS, PushPermissionStatus.GRANTED, true, NOW));
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));

    PushDevice result =
        service.register(
            user(),
            DEVICE_ID,
            new PushDeviceRegistration(
                PushPlatform.IOS,
                "__REDACTED_REGISTRATION_TOKEN__",
                PushPermissionStatus.GRANTED,
                "1.2.3",
                "ko-KR",
                "Asia/Seoul"));

    assertThat(result.deviceId()).isEqualTo(DEVICE_ID);
    verify(store)
        .register(
            eq(USER_ID),
            eq(DEVICE_ID),
            org.mockito.ArgumentMatchers.argThat(
                value ->
                    value.tokenCiphertext().equals("v1.ciphertext")
                        && java.util.Arrays.equals(value.tokenFingerprint(), fingerprint)
                        && !value.tokenCiphertext().contains("__REDACTED_REGISTRATION_TOKEN__")),
            eq(NOW));
  }

  @Test
  void 해제는_자기_userId와_deviceId만_멱등_store에_전달한다() {
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));

    service.invalidate(user(), DEVICE_ID);

    verify(store).invalidate(USER_ID, DEVICE_ID, NOW);
  }

  @Test
  void registrationToken은_ASCII_4096byte까지_허용하고_초과와_Unicode는_crypto전_거부한다() {
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    String max = "A".repeat(4096);
    byte[] fingerprint = new byte[32];
    when(tokens.protect(max)).thenReturn(new ProtectedRegistrationToken("ciphertext", fingerprint));
    when(store.register(eq(USER_ID), eq(DEVICE_ID), any(), eq(NOW)))
        .thenReturn(
            new PushDevice(DEVICE_ID, PushPlatform.IOS, PushPermissionStatus.GRANTED, true, NOW));

    service.register(user(), DEVICE_ID, registration(max));

    for (String invalid : new String[] {"A".repeat(4097), "가".repeat(2000)}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.register(user(), DEVICE_ID, registration(invalid)))
          .isInstanceOf(PushNotificationException.class)
          .satisfies(
              failure ->
                  assertThat(((PushNotificationException) failure).code())
                      .isEqualTo("INVALID_PUSH_NOTIFICATION_REQUEST"));
    }
    verify(tokens, never()).protect("A".repeat(4097));
    verify(tokens, never()).protect("가".repeat(2000));
  }

  @Test
  void typed_crypto실패만_503_application오류로_변환하고_store와_cause_token을_노출하지_않는다() {
    String token = "__REDACTED_CRYPTO_FAILURE_TOKEN__";
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    when(tokens.protect(token)).thenThrow(new RegistrationTokenProtectionFailure());

    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.register(user(), DEVICE_ID, registration(token)));

    assertThat(failure).isInstanceOf(PushNotificationException.class).hasNoCause();
    assertThat(((PushNotificationException) failure).code())
        .isEqualTo("PUSH_NOTIFICATION_DATA_UNAVAILABLE");
    assertThat(failure.toString()).doesNotContain(token).doesNotContain("provider leaked");
    verify(store, never()).register(any(), any(), any(), any());
  }

  @Test
  void programmer_RuntimeException은_503으로_숨기지_않고_그대로_전파하되_store를_호출하지_않는다() {
    String token = "__REDACTED_PROGRAMMER_FAILURE_TOKEN__";
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    var programmerFailure = new IllegalStateException("programmer failure");
    when(tokens.protect(token)).thenThrow(programmerFailure);

    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.register(user(), DEVICE_ID, registration(token)));

    assertThat(failure).isSameAs(programmerFailure);
    verify(store, never()).register(any(), any(), any(), any());
  }

  @Test
  void locale은_canonical_BCP47_확장과_35자까지_허용하고_case_36자_invalid를_crypto전_거부한다() {
    var service = new PushDeviceService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    for (String locale :
        new String[] {"en-US-u-ca-gregory", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-ddd"}) {
      String token = "token-" + locale;
      when(tokens.protect(token))
          .thenReturn(new ProtectedRegistrationToken("ciphertext", new byte[32]));
      when(store.register(eq(USER_ID), eq(DEVICE_ID), any(), eq(NOW)))
          .thenReturn(
              new PushDevice(DEVICE_ID, PushPlatform.IOS, PushPermissionStatus.GRANTED, true, NOW));
      service.register(user(), DEVICE_ID, registration(token, locale));
    }

    for (String locale :
        new String[] {"en-us", "en-x-aaaaaaaa-bbbbbbbb-cccccccc-dddd", "invalid_locale"}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> service.register(user(), DEVICE_ID, registration("token-invalid", locale)))
          .isInstanceOf(PushNotificationException.class);
    }
    verify(tokens, never()).protect("token-invalid");
  }

  private static PushDeviceRegistration registration(String token) {
    return registration(token, "ko-KR");
  }

  private static PushDeviceRegistration registration(String token, String locale) {
    return new PushDeviceRegistration(
        PushPlatform.IOS, token, PushPermissionStatus.GRANTED, "1.2.3", locale, "Asia/Seoul");
  }

  private static CurrentUser user() {
    return new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);
  }
}
