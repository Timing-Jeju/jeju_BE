package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.profile.ProfileProvisioningException;
import com.timingjeju.api.application.security.AuthenticatedRole;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.service.TripService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripServiceTest {

  private static final UUID OWNER = UUID.fromString("44000000-0000-0000-0000-000000000001");
  private static final CurrentUser USER =
      new CurrentUser(OWNER, AuthenticatedRole.AUTHENTICATED, null);
  private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

  @Test
  void create는_날짜별_day_id를_모아_store를_한번만_호출한다() {
    CapturingStore store = new CapturingStore();
    TripService service = service(store);

    TripAggregate created =
        service.create(
            USER,
            new CreateTripCommand(
                "제주 동쪽 2박 3일",
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-05"),
                "Asia/Seoul",
                "normal",
                List.of(new TripTransportMode("public_transit", 1, true))));

    assertThat(store.createCalls).isEqualTo(1);
    assertThat(store.created.dayIds()).hasSize(3).doesNotHaveDuplicates();
    assertThat(created.days())
        .extracting(TripDay::date)
        .containsExactly(
            LocalDate.parse("2026-08-03"),
            LocalDate.parse("2026-08-04"),
            LocalDate.parse("2026-08-05"));
  }

  @Test
  void create는_profile_provisioning_실패_4종에서_store를_호출하지_않는다() {
    for (ProfileProvisioningException failure :
        List.of(
            ProfileProvisioningException.emailConflict(),
            ProfileProvisioningException.providerSubjectConflict(),
            ProfileProvisioningException.invalidAuthIdentity(),
            ProfileProvisioningException.storageUnavailable())) {
      CapturingStore store = new CapturingStore();
      CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
      doThrow(failure).when(provisioning).provision(USER);
      TripService service =
          new TripService(
              provisioning,
              store,
              new SequentialIds(),
              CursorCodec.hmacSha256("test-only-trip-cursor-key-32-bytes"),
              Clock.fixed(NOW, ZoneOffset.UTC));

      assertThatThrownBy(() -> service.create(USER, command("2026-08-03", "2026-08-05")))
          .isSameAs(failure);
      assertThat(store.createCalls).isZero();
      assertThat(store.created).isNull();
    }
  }

  @Test
  void create는_1일과_30일을_허용하고_역전과_31일을_거부한다() {
    TripService service = service(new CapturingStore());

    service.create(USER, command("2026-08-01", "2026-08-01"));
    service.create(USER, command("2026-08-01", "2026-08-30"));

    assertThatThrownBy(() -> service.create(USER, command("2026-08-02", "2026-08-01")))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_CONSTRAINT_VIOLATION");
    assertThatThrownBy(() -> service.create(USER, command("2026-08-01", "2026-08-31")))
        .isInstanceOf(TripException.class);
  }

  @Test
  void read는_owner조건으로만_조회하고_없으면_동일한_not_found를_반환한다() {
    CapturingStore store = new CapturingStore();
    TripService service = service(store);
    UUID unknown = UUID.fromString("44000000-0000-0000-0000-000000000099");

    assertThatThrownBy(() -> service.read(USER, unknown))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_NOT_FOUND");
    assertThat(store.readOwner).isEqualTo(OWNER);
  }

  @Test
  void list는_size보다_한건_더_조회하고_다음_cursor를_발급한다() {
    CapturingStore store = new CapturingStore();
    store.listRows = List.of(summary(3), summary(2), summary(1));
    TripService service = service(store);

    TripPage page = service.list(USER, "planned", "updated_at_desc", null, 2);

    assertThat(store.fetchSize).isEqualTo(3);
    assertThat(store.listOwner).isEqualTo(OWNER);
    assertThat(page.items()).hasSize(2);
    assertThat(page.hasNext()).isTrue();
    assertThat(page.nextCursor()).isNotBlank();
  }

  @Test
  void create는_제목_timezone_pace와_교통_우선순위_경계를_거부한다() {
    TripService service = service(new CapturingStore());
    CreateTripCommand valid = command("2026-08-01", "2026-08-03");

    assertInvalid(
        service,
        new CreateTripCommand(
            " ",
            valid.startDate(),
            valid.endDate(),
            valid.timezone(),
            valid.userPace(),
            valid.transportModes()));
    assertInvalid(
        service,
        new CreateTripCommand(
            valid.title(),
            valid.startDate(),
            valid.endDate(),
            "UTC",
            valid.userPace(),
            valid.transportModes()));
    assertInvalid(
        service,
        new CreateTripCommand(
            valid.title(),
            valid.startDate(),
            valid.endDate(),
            valid.timezone(),
            "rapid",
            valid.transportModes()));
    assertConstraint(
        service,
        new CreateTripCommand(
            valid.title(),
            valid.startDate(),
            valid.endDate(),
            valid.timezone(),
            valid.userPace(),
            List.of(
                new TripTransportMode("taxi", 1, false),
                new TripTransportMode("public_transit", 2, true))));
  }

  @Test
  void list는_잘못된_status_size_sort와_cursor를_fail_closed한다() {
    TripService service = service(new CapturingStore());

    assertThatThrownBy(() -> service.list(USER, "unknown", null, null, 20))
        .isInstanceOf(TripException.class);
    assertThatThrownBy(() -> service.list(USER, null, "created_at_desc", null, 20))
        .isInstanceOf(TripException.class);
    assertThatThrownBy(() -> service.list(USER, null, null, null, 51))
        .isInstanceOf(TripException.class);
    assertThatThrownBy(() -> service.list(USER, null, null, "not-a-cursor", 20))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("INVALID_CURSOR");
  }

  @Test
  void list는_발급_status와_다른_cursor_context를_별도_code로_거부한다() {
    CapturingStore store = new CapturingStore();
    store.listRows = List.of(summary(2), summary(1));
    TripService service = service(store);
    String cursor = service.list(USER, "planned", null, null, 1).nextCursor();

    assertThatThrownBy(() -> service.list(USER, "live", null, cursor, 1))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("CURSOR_CONTEXT_MISMATCH");
  }

  @Test
  void create는_title을_ASCII_trim후_NFC로_정규화하고_공백만_있으면_거부한다() {
    CapturingStore store = new CapturingStore();
    TripService service = service(store);
    CreateTripCommand base = command("2026-08-01", "2026-08-01");

    service.create(
        USER,
        new CreateTripCommand(
            " \t\u110C\u1166\u110C\u116E 여행\r\n",
            base.startDate(),
            base.endDate(),
            base.timezone(),
            base.userPace(),
            base.transportModes()));

    assertThat(store.created.command().title()).isEqualTo("제주 여행");
    assertInvalid(
        service,
        new CreateTripCommand(
            " \t\r\n ",
            base.startDate(),
            base.endDate(),
            base.timezone(),
            base.userPace(),
            base.transportModes()));
  }

  private static void assertInvalid(TripService service, CreateTripCommand command) {
    assertThatThrownBy(() -> service.create(USER, command))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("INVALID_REQUEST");
  }

  private static void assertConstraint(TripService service, CreateTripCommand command) {
    assertThatThrownBy(() -> service.create(USER, command))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("TRIP_CONSTRAINT_VIOLATION");
  }

  private static TripService service(TripStore store) {
    CurrentUserProvisioningService provisioning = mock(CurrentUserProvisioningService.class);
    return new TripService(
        provisioning,
        store,
        new SequentialIds(),
        CursorCodec.hmacSha256("test-only-trip-cursor-key-32-bytes"),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static CreateTripCommand command(String start, String end) {
    return new CreateTripCommand(
        "제주 여행",
        LocalDate.parse(start),
        LocalDate.parse(end),
        "Asia/Seoul",
        "normal",
        List.of(new TripTransportMode("public_transit", 1, true)));
  }

  private static TripSummary summary(int suffix) {
    UUID id = UUID.fromString("44000000-0000-0000-0000-%012d".formatted(suffix));
    return new TripSummary(
        id,
        "여행 " + suffix,
        "planned",
        LocalDate.parse("2026-08-01"),
        LocalDate.parse("2026-08-01"),
        "Asia/Seoul",
        null,
        null,
        null,
        NOW.minusSeconds(suffix),
        NOW.minusSeconds(suffix));
  }

  private static final class SequentialIds implements TripIdentityGenerator {
    private int value = 1;

    @Override
    public UUID generate() {
      return UUID.fromString("44000000-0000-0000-0001-%012d".formatted(value++));
    }
  }

  private static final class CapturingStore implements TripStore {
    private int createCalls;
    private CreateTripRecord created;
    private UUID readOwner;
    private UUID listOwner;
    private int fetchSize;
    private List<TripSummary> listRows = List.of();

    @Override
    public TripAggregate create(CreateTripRecord record) {
      createCalls++;
      created = record;
      List<TripDay> days = new java.util.ArrayList<>();
      for (int index = 0; index < record.dayIds().size(); index++) {
        days.add(
            new TripDay(
                record.dayIds().get(index),
                index + 1,
                record.command().startDate().plusDays(index)));
      }
      return new TripAggregate(
          record.tripId(),
          1,
          record.command().title(),
          "draft",
          record.command().startDate(),
          record.command().endDate(),
          record.command().timezone(),
          record.command().userPace(),
          record.command().transportModes(),
          days,
          null,
          null,
          null,
          record.createdAt(),
          record.createdAt());
    }

    @Override
    public Optional<TripAggregate> findOwned(UUID ownerId, UUID tripId, Instant responseTime) {
      readOwner = ownerId;
      return Optional.empty();
    }

    @Override
    public TripListSlice listOwned(
        UUID ownerId,
        String status,
        TripListCursor after,
        int requestedFetchSize,
        Instant responseTime) {
      listOwner = ownerId;
      fetchSize = requestedFetchSize;
      return new TripListSlice(listRows);
    }

    @Override
    public com.timingjeju.api.application.trip.TripMutationResult updateOwned(
        com.timingjeju.api.application.trip.TripUpdateRecord record) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TripPreferencesMutation replacePreferences(ReplaceTripPreferencesRecord record) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteOwned(UUID ownerId, UUID tripId) {
      throw new UnsupportedOperationException();
    }
  }
}
