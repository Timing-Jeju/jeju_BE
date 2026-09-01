package com.timingjeju.api.application.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.application.schedule.service.ScheduleQueryService;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleQueryServiceTest {
  private static final UUID OWNER = UUID.fromString("49000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("49000000-0000-0000-0000-000000000002");
  private static final UUID VERSION = UUID.fromString("49000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);

  @Test
  void read는_owner와_선택자를_포트에_전달하고_발견한_일정을_반환한다() {
    CapturingStore store = new CapturingStore();
    ScheduleSnapshot expected =
        new ScheduleSnapshot(
            TRIP,
            new ScheduleVersionSnapshot(VERSION, 1, "active", "initial", null, 81, false),
            List.of());
    store.result = ScheduleLookup.found(expected);

    ScheduleSnapshot actual = service(store).read(USER, TRIP, VERSION);

    assertThat(actual).isEqualTo(expected);
    assertThat(store.ownerId).isEqualTo(OWNER);
    assertThat(store.tripId).isEqualTo(TRIP);
    assertThat(store.versionId).isEqualTo(VERSION);
    assertThat(store.responseTime).isEqualTo(NOW);
  }

  @Test
  void read는_trip과_schedule_version_부재를_서로_다른_공개_code로_변환한다() {
    CapturingStore store = new CapturingStore();
    store.result = ScheduleLookup.tripNotFound();

    assertThatThrownBy(() -> service(store).read(USER, TRIP, null))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("TRIP_NOT_FOUND");

    store.result = ScheduleLookup.versionNotFound();
    assertThatThrownBy(() -> service(store).read(USER, TRIP, VERSION))
        .isInstanceOf(ScheduleException.class)
        .extracting(failure -> ((ScheduleException) failure).code())
        .isEqualTo("SCHEDULE_VERSION_NOT_FOUND");
  }

  private static ScheduleQueryService service(ScheduleStore store) {
    return new ScheduleQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static final class CapturingStore implements ScheduleStore {
    private UUID ownerId;
    private UUID tripId;
    private UUID versionId;
    private Instant responseTime;
    private ScheduleLookup result;

    @Override
    public ScheduleLookup readOwned(
        UUID ownerId, UUID tripId, UUID versionId, Instant responseTime) {
      this.ownerId = ownerId;
      this.tripId = tripId;
      this.versionId = versionId;
      this.responseTime = responseTime;
      return result;
    }
  }
}
