package com.timingjeju.api.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.notification.service.PushNotificationEligibilityService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PushNotificationEligibilityServiceTest {

  private static final UUID USER_ID = UUID.fromString("11300000-0000-0000-0000-000000000001");
  private static final UUID DEVICE_ID = UUID.fromString("11300000-0000-0000-0000-000000000101");
  private static final UUID DOCUMENT_ID = UUID.fromString("09200000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");
  private static final String CIPHERTEXT = "v1.redacted-ciphertext";

  @Mock private PushEligibilityStore store;
  @Mock private RegistrationTokenProtector tokens;

  @Test
  void typed_reveal실패만_cause없는_503으로_변환한다() {
    when(store.findEligible(USER_ID, NOW)).thenReturn(List.of(stored()));
    when(tokens.reveal(CIPHERTEXT)).thenThrow(new RegistrationTokenProtectionFailure());

    Throwable failure = catchThrowable(() -> service().findEligible(USER_ID));

    assertThat(failure).isInstanceOf(PushNotificationException.class).hasNoCause();
    assertThat(((PushNotificationException) failure).code())
        .isEqualTo("PUSH_NOTIFICATION_DATA_UNAVAILABLE");
  }

  @Test
  void programmer_reveal실패는_503으로_숨기지_않는다() {
    var programmerFailure = new IllegalStateException("programmer failure");
    when(store.findEligible(USER_ID, NOW)).thenReturn(List.of(stored()));
    when(tokens.reveal(CIPHERTEXT)).thenThrow(programmerFailure);

    assertThat(catchThrowable(() -> service().findEligible(USER_ID))).isSameAs(programmerFailure);
  }

  private PushNotificationEligibilityService service() {
    return new PushNotificationEligibilityService(store, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static StoredEligiblePushDevice stored() {
    return new StoredEligiblePushDevice(
        DEVICE_ID, PushPlatform.IOS, CIPHERTEXT, 10, DOCUMENT_ID, "2026-08-11.v1");
  }
}
