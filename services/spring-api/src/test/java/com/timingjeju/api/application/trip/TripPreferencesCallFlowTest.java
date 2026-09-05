package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.service.TripService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("unit")
class TripPreferencesCallFlowTest {
  private static final UUID OWNER = UUID.fromString("46000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("46000000-0000-0000-0000-000000000002");
  private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

  @Test
  void service는_정규화한_replace를_TripStore에_정확히_한번만_위임하고_응답은_store_reload를_그대로_쓴다() {
    TripStore store = mock(TripStore.class);
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    TripPreferencesMutation reloaded =
        new TripPreferencesMutation(
            TRIP,
            command(List.of(new TripTransportMode("taxi", 1, true))),
            8,
            NOW,
            "none",
            false,
            null,
            "draft",
            "\"trip-8\"");
    when(store.replacePreferences(any())).thenReturn(reloaded);
    TripService service =
        new TripService(
            provisioning,
            store,
            mock(TripIdentityGenerator.class),
            mock(CursorCodec.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    TripPreferencesMutation result =
        service.replacePreferences(
            new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null),
            TRIP,
            7,
            command(List.of(new TripTransportMode("public_transit", 1, true))));

    assertThat(result).isSameAs(reloaded);
    ArgumentCaptor<ReplaceTripPreferencesRecord> record =
        ArgumentCaptor.forClass(ReplaceTripPreferencesRecord.class);
    verify(store).replacePreferences(record.capture());
    assertThat(record.getValue().ownerId()).isEqualTo(OWNER);
    assertThat(record.getValue().tripId()).isEqualTo(TRIP);
    assertThat(record.getValue().expectedRevision()).isEqualTo(7);
    assertThat(record.getValue().updatedAt()).isEqualTo(NOW);
    assertThat(record.getValue().command().arrivalRegionCode()).isEqualTo("제주시");
    verify(store, never()).updateOwned(any());
    verifyNoInteractions(provisioning);
  }

  private static ReplaceTripPreferencesCommand command(List<TripTransportMode> modes) {
    return new ReplaceTripPreferencesCommand(
        List.of("cafe"),
        " \u110C\u1166\u110C\u116E\u1109\u1175 ",
        "seogwipo-si",
        List.of(),
        null,
        null,
        modes);
  }
}
