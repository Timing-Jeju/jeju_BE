package com.timingjeju.api.application.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.accommodation.service.AccommodationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class AccommodationServiceTest {
  private static final UUID OWNER = UUID.fromString("68000000-0000-0000-0000-000000000001");
  private static final UUID TRIP = UUID.fromString("68000000-0000-0000-0000-000000000002");
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-09-01T06:00:00Z");

  @Test
  void create는_customName을_ASCII_trim_NFC하고_canonical_key와_expected_revision을_전달한다()
      throws Exception {
    CapturingStore store = new CapturingStore();
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsBytes(any()))
        .thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    AccommodationService service = service(store, mapper);

    AccommodationHttpResult result =
        service.create(
            OWNER,
            TRIP,
            "client key,2026/09?retry=1",
            7,
            new CreateAccommodationCommand(
                null,
                "  \u110C\u1166\u110C\u116E 숙소  ",
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"),
                LocalTime.parse("15:00"),
                LocalTime.parse("11:00")));

    assertThat(store.created.command().customName()).isEqualTo("제주 숙소");
    assertThat(store.created.idempotencyKey()).isEqualTo("client key,2026/09?retry=1");
    assertThat(store.created.expectedRevision()).isEqualTo(7);
    assertThat(store.completedSnapshot.etag()).isEqualTo("\"trip-" + TRIP + "-r8\"");
    assertThat(result.replayed()).isFalse();
  }

  @Test
  void create는_XOR_key_name경계를_store전에_거부한다() {
    CapturingStore store = new CapturingStore();
    AccommodationService service = service(store, mock(ObjectMapper.class));
    long expected = 1;
    String validKey = "68000000-0000-0000-0000-000000000099";

    for (String invalid :
        List.of("control\u001fkey", "delete\u007fkey", "비ASCII", "Z".repeat(129))) {
      assertCode(
          () -> service.create(OWNER, TRIP, invalid, expected, create(null, "숙소")),
          "INVALID_REQUEST");
    }
    assertCode(
        () -> service.create(OWNER, TRIP, validKey, expected, create(null, null)),
        "INVALID_REQUEST");
    assertCode(
        () ->
            service.create(
                OWNER,
                TRIP,
                validKey,
                expected,
                create(UUID.fromString("68000000-0000-0000-0000-000000000010"), "숙소")),
        "INVALID_REQUEST");
    assertCode(
        () -> service.create(OWNER, TRIP, validKey, expected, create(null, "가".repeat(101))),
        "INVALID_REQUEST");
    assertThat(store.created).isNull();
  }

  @Test
  void create는_100_codepoint_name과_canonical_UUID_key를_허용한다() throws Exception {
    CapturingStore store = new CapturingStore();
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsBytes(any()))
        .thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    service(store, mapper)
        .create(
            OWNER, TRIP, "68000000-0000-0000-0000-000000000099", 1, create(null, "가".repeat(100)));

    assertThat(store.created.command().customName()).hasSize(100);
  }

  @Test
  void patch는_empty와_nonidentity_null을_거부하고_identity_null전환은_허용한다() throws Exception {
    CapturingStore store = new CapturingStore();
    ObjectMapper mapper = mock(ObjectMapper.class);
    when(mapper.writeValueAsBytes(any()))
        .thenReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    AccommodationService service = service(store, mapper);
    long expected = 3;

    assertCode(
        () ->
            service.patch(OWNER, TRIP, ACCOMMODATION, expected, PatchAccommodationCommand.empty()),
        "INVALID_REQUEST");
    assertCode(
        () ->
            service.patch(
                OWNER,
                TRIP,
                ACCOMMODATION,
                expected,
                PatchAccommodationCommand.withCheckInTime(AccommodationPatchValue.present(null))),
        "INVALID_REQUEST");

    service.patch(
        OWNER,
        TRIP,
        ACCOMMODATION,
        expected,
        PatchAccommodationCommand.identity(
            AccommodationPatchValue.present(null), AccommodationPatchValue.present("  새 숙소  ")));
    assertThat(store.patched.command().customName().value()).isEqualTo("새 숙소");
    assertThat(store.patched.command().placeId().value()).isNull();
  }

  @Test
  void delete는_owner_trip_accommodation_expected를_store에_그대로_전달한다() {
    CapturingStore store = new CapturingStore();

    service(store, mock(ObjectMapper.class)).delete(OWNER, TRIP, ACCOMMODATION, 4);

    assertThat(store.deleted.ownerId()).isEqualTo(OWNER);
    assertThat(store.deleted.tripId()).isEqualTo(TRIP);
    assertThat(store.deleted.accommodationId()).isEqualTo(ACCOMMODATION);
    assertThat(store.deleted.expectedRevision()).isEqualTo(4);
  }

  private static AccommodationService service(AccommodationStore store, ObjectMapper mapper) {
    return new AccommodationService(
        store, () -> ACCOMMODATION, Clock.fixed(NOW, ZoneOffset.UTC), mapper);
  }

  private static CreateAccommodationCommand create(UUID placeId, String customName) {
    return new CreateAccommodationCommand(
        placeId,
        customName,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-02"),
        LocalTime.parse("15:00"),
        LocalTime.parse("11:00"));
  }

  private static AccommodationMutation mutation(long revision) {
    Accommodation accommodation =
        new Accommodation(
            ACCOMMODATION,
            null,
            "제주 숙소",
            "제주 숙소",
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-02"),
            LocalTime.parse("15:00"),
            LocalTime.parse("11:00"),
            1,
            NOW,
            NOW);
    return new AccommodationMutation(TRIP, accommodation, "none", false, null, "draft", revision);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(AccommodationException.class)
        .extracting(failure -> ((AccommodationException) failure).code())
        .isEqualTo(code);
  }

  private static final class CapturingStore implements AccommodationStore {
    private AccommodationCreateRecord created;
    private AccommodationPatchRecord patched;
    private AccommodationDeleteRecord deleted;
    private AccommodationHttpSnapshot completedSnapshot;

    @Override
    public AccommodationCreateStoreResult create(AccommodationCreateRecord record) {
      created = record;
      return AccommodationCreateStoreResult.created(mutation(8));
    }

    @Override
    public void completeCreateSnapshot(
        UUID ownerId, UUID tripId, String key, AccommodationHttpSnapshot snapshot) {
      completedSnapshot = snapshot;
    }

    @Override
    public AccommodationMutation patch(AccommodationPatchRecord record) {
      patched = record;
      return mutation(4);
    }

    @Override
    public void delete(AccommodationDeleteRecord record) {
      deleted = record;
    }
  }
}
