package com.timingjeju.api.application.trip.service;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.pagination.CursorContext;
import com.timingjeju.api.application.pagination.CursorContextMismatchException;
import com.timingjeju.api.application.pagination.CursorFilterFingerprint;
import com.timingjeju.api.application.pagination.CursorInvalidException;
import com.timingjeju.api.application.pagination.CursorPosition;
import com.timingjeju.api.application.pagination.CursorSort;
import com.timingjeju.api.application.profile.CurrentUserProvisioningService;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.trip.CreateTripCommand;
import com.timingjeju.api.application.trip.CreateTripRecord;
import com.timingjeju.api.application.trip.PatchTripCommand;
import com.timingjeju.api.application.trip.ReplaceTripPreferencesCommand;
import com.timingjeju.api.application.trip.ReplaceTripPreferencesRecord;
import com.timingjeju.api.application.trip.TripAggregate;
import com.timingjeju.api.application.trip.TripException;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import com.timingjeju.api.application.trip.TripIdentityGenerator;
import com.timingjeju.api.application.trip.TripListCursor;
import com.timingjeju.api.application.trip.TripListSlice;
import com.timingjeju.api.application.trip.TripMutationResult;
import com.timingjeju.api.application.trip.TripPage;
import com.timingjeju.api.application.trip.TripPatchValue;
import com.timingjeju.api.application.trip.TripPreferencePolicy;
import com.timingjeju.api.application.trip.TripPreferencesMutation;
import com.timingjeju.api.application.trip.TripStore;
import com.timingjeju.api.application.trip.TripSummary;
import com.timingjeju.api.application.trip.TripTransportMode;
import com.timingjeju.api.application.trip.TripUpdateRecord;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TripService {
  private static final Set<String> STATUSES =
      Set.of("draft", "generating", "planned", "live", "completed", "cancelled", "failed");
  private static final CursorSort SORT = CursorSort.desc("updatedAt", "tripId");
  private static final String ENDPOINT = "/api/v1/trips";

  private final CurrentUserProvisioningService provisioning;
  private final TripStore trips;
  private final TripIdentityGenerator identities;
  private final CursorCodec cursorCodec;
  private final Clock clock;

  public TripService(
      CurrentUserProvisioningService provisioning,
      TripStore trips,
      TripIdentityGenerator identities,
      CursorCodec cursorCodec,
      Clock clock) {
    this.provisioning = Objects.requireNonNull(provisioning);
    this.trips = Objects.requireNonNull(trips);
    this.identities = Objects.requireNonNull(identities);
    this.cursorCodec = Objects.requireNonNull(cursorCodec);
    this.clock = Objects.requireNonNull(clock);
  }

  public TripAggregate create(CurrentUser user, CreateTripCommand command) {
    Objects.requireNonNull(user);
    CreateTripCommand canonical = canonicalize(command);
    validate(canonical);
    provisioning.provision(user);
    long days = ChronoUnit.DAYS.between(canonical.startDate(), canonical.endDate()) + 1;
    List<UUID> dayIds = new ArrayList<>((int) days);
    for (int day = 0; day < days; day++) {
      dayIds.add(identities.generate());
    }
    return trips.create(
        new CreateTripRecord(
            user.userId(),
            identities.generate(),
            identities.generate().toString(),
            canonical,
            dayIds,
            clock.instant()));
  }

  public TripAggregate read(CurrentUser user, UUID tripId) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    return trips
        .findOwned(user.userId(), tripId, clock.instant())
        .orElseThrow(TripException::notFound);
  }

  public TripMutationResult update(
      CurrentUser user, UUID tripId, TripExpectedRevision expected, PatchTripCommand command) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expected);
    PatchTripCommand canonical = canonicalize(command);
    validate(canonical);
    List<UUID> dayIds = new ArrayList<>(30);
    for (int day = 0; day < 30; day++) {
      dayIds.add(identities.generate());
    }
    return trips.updateOwned(
        new TripUpdateRecord(user.userId(), tripId, expected, canonical, dayIds, clock.instant()));
  }

  public void delete(CurrentUser user, UUID tripId) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    trips.deleteOwned(user.userId(), tripId);
  }

  public TripPreferencesMutation replacePreferences(
      CurrentUser user, UUID tripId, long expectedRevision, ReplaceTripPreferencesCommand command) {
    Objects.requireNonNull(user);
    Objects.requireNonNull(tripId);
    ReplaceTripPreferencesCommand canonical = TripPreferencePolicy.canonicalizeAndValidate(command);
    return trips.replacePreferences(
        new ReplaceTripPreferencesRecord(
            user.userId(), tripId, expectedRevision, canonical, clock.instant()));
  }

  public TripPage list(
      CurrentUser user, String status, String sort, String encodedCursor, Integer requestedSize) {
    Objects.requireNonNull(user);
    String normalizedStatus = normalizeStatus(status);
    if (sort != null && !"updated_at_desc".equals(sort)) {
      throw TripException.invalidQuery();
    }
    int size = requestedSize == null ? 20 : requestedSize;
    if (size < 1 || size > 50) {
      throw TripException.invalidQuery();
    }
    CursorContext context =
        new CursorContext(
            ENDPOINT,
            SORT,
            CursorFilterFingerprint.sha256(
                Map.of(
                    "owner",
                    user.userId().toString(),
                    "status",
                    Objects.toString(normalizedStatus, ""))));
    TripListCursor after = decode(encodedCursor, context);
    Instant responseTime = clock.instant();
    TripListSlice slice =
        trips.listOwned(user.userId(), normalizedStatus, after, size + 1, responseTime);
    boolean hasNext = slice.rows().size() > size;
    List<TripSummary> items = hasNext ? slice.rows().subList(0, size) : slice.rows();
    String next = null;
    if (hasNext) {
      TripSummary last = items.getLast();
      next =
          cursorCodec.encode(
              context, new CursorPosition(last.updatedAt().toString(), last.tripId().toString()));
    }
    return new TripPage(items, size, hasNext, next);
  }

  private TripListCursor decode(String cursor, CursorContext context) {
    if (cursor == null) {
      return null;
    }
    CursorPosition position;
    try {
      position = cursorCodec.decode(cursor, context);
    } catch (CursorContextMismatchException failure) {
      throw TripException.cursorContextMismatch();
    } catch (CursorInvalidException failure) {
      throw TripException.invalidCursor();
    }
    try {
      return new TripListCursor(
          Instant.parse(position.sortValue()), UUID.fromString(position.tieBreaker()));
    } catch (RuntimeException failure) {
      throw TripException.invalidCursor();
    }
  }

  private static String normalizeStatus(String status) {
    if (status == null) {
      return null;
    }
    if (!STATUSES.contains(status)) {
      throw TripException.invalidQuery();
    }
    return status;
  }

  private static void validate(CreateTripCommand command) {
    Objects.requireNonNull(command);
    if (command.title() == null
        || command.title().isBlank()
        || command.title().length() > 100
        || !Normalizer.isNormalized(command.title(), Normalizer.Form.NFC)) {
      throw TripException.invalidRequest();
    }
    if (command.startDate() == null || command.endDate() == null) {
      throw TripException.invalidRequest();
    }
    long days = ChronoUnit.DAYS.between(command.startDate(), command.endDate()) + 1;
    if (days < 1 || days > 30) {
      throw TripException.constraintViolation();
    }
    if (!"Asia/Seoul".equals(command.timezone())
        || command.userPace() == null
        || !Set.of("slow", "normal", "fast").contains(command.userPace())) {
      throw TripException.invalidRequest();
    }
    validateTransportModes(command.transportModes());
  }

  private static void validate(PatchTripCommand command) {
    Objects.requireNonNull(command);
    if (command.emptyPatch()) {
      throw TripException.invalidRequest();
    }
    if (command.title().present()) {
      validateTitle(command.title().value());
    }
    if (command.timezone().present() && !"Asia/Seoul".equals(command.timezone().value())) {
      throw TripException.invalidRequest();
    }
    if (command.userPace().present()
        && !Set.of("slow", "normal", "fast").contains(command.userPace().value())) {
      throw TripException.invalidRequest();
    }
    if (command.transportModes().present()) {
      validateTransportModes(command.transportModes().value());
    }
  }

  private static void validateTitle(String title) {
    if (title == null
        || title.isBlank()
        || title.length() > 100
        || !Normalizer.isNormalized(title, Normalizer.Form.NFC)) {
      throw TripException.invalidRequest();
    }
  }

  private static void validateTransportModes(List<TripTransportMode> transportModes) {
    if (transportModes.isEmpty() || transportModes.size() > 3) {
      throw TripException.constraintViolation();
    }
    Set<String> names = new java.util.HashSet<>();
    int primaryCount = 0;
    for (int index = 0; index < transportModes.size(); index++) {
      var mode = transportModes.get(index);
      if (mode == null
          || mode.mode() == null
          || !Set.of("public_transit", "rental_car", "taxi").contains(mode.mode())
          || mode.priority() != index + 1
          || !names.add(mode.mode())) {
        throw TripException.constraintViolation();
      }
      if (mode.primary()) {
        primaryCount++;
        if (mode.priority() != 1) {
          throw TripException.constraintViolation();
        }
      }
    }
    if (primaryCount != 1) {
      throw TripException.constraintViolation();
    }
  }

  private static CreateTripCommand canonicalize(CreateTripCommand command) {
    Objects.requireNonNull(command);
    String title = command.title();
    String canonicalTitle =
        title == null ? null : Normalizer.normalize(asciiTrim(title), Normalizer.Form.NFC);
    return new CreateTripCommand(
        canonicalTitle,
        command.startDate(),
        command.endDate(),
        command.timezone(),
        command.userPace(),
        command.transportModes());
  }

  private static PatchTripCommand canonicalize(PatchTripCommand command) {
    Objects.requireNonNull(command);
    TripPatchValue<String> title = command.title();
    if (title.present()) {
      title =
          TripPatchValue.present(
              Normalizer.normalize(asciiTrim(title.value()), Normalizer.Form.NFC));
    }
    return new PatchTripCommand(
        title,
        command.startDate(),
        command.endDate(),
        command.timezone(),
        command.userPace(),
        command.transportModes());
  }

  private static String asciiTrim(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) <= 0x20) {
      start++;
    }
    while (end > start && value.charAt(end - 1) <= 0x20) {
      end--;
    }
    return value.substring(start, end);
  }
}
