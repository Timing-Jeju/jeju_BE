package com.timingjeju.api.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.notification.service.NotificationPreferenceService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceTest {

  private static final UUID USER_ID = UUID.fromString("11300000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-26T01:02:03Z");

  @Mock private NotificationPreferenceStore store;

  @Test
  void 최초조회는_명시적_optIn전까지_false와_safety10을_반환한다() {
    when(store.find(USER_ID)).thenReturn(Optional.empty());
    var service = new NotificationPreferenceService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    NotificationPreference result = service.read(user());

    assertThat(result.nextDestinationDepartureEnabled()).isFalse();
    assertThat(result.safetyBufferMinutes()).isEqualTo(10);
    assertThat(result.updatedAt()).isNull();
  }

  @Test
  void PATCH는_omitted값을_보존하고_0과120_inclusive를_store에_전달한다() {
    var current = new NotificationPreference(true, 120, NOW.minusSeconds(60));
    when(store.find(USER_ID)).thenReturn(Optional.of(current));
    when(store.save(USER_ID, new NotificationPreferenceUpdate(true, 0), NOW))
        .thenReturn(new NotificationPreference(true, 0, NOW));
    var service = new NotificationPreferenceService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    NotificationPreference result =
        service.update(user(), new NotificationPreferencePatch(false, null, true, 0));

    assertThat(result.safetyBufferMinutes()).isZero();
    verify(store).save(USER_ID, new NotificationPreferenceUpdate(true, 0), NOW);
  }

  private static CurrentUser user() {
    return new CurrentUser(USER_ID, AuthenticatedRole.AUTHENTICATED, null);
  }
}
