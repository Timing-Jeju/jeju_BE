package com.timingjeju.api.application.accommodation.service;

import com.timingjeju.api.application.accommodation.AccommodationCreateRecord;
import com.timingjeju.api.application.accommodation.AccommodationCreateStoreResult;
import com.timingjeju.api.application.accommodation.AccommodationDeleteRecord;
import com.timingjeju.api.application.accommodation.AccommodationException;
import com.timingjeju.api.application.accommodation.AccommodationHttpResult;
import com.timingjeju.api.application.accommodation.AccommodationHttpSnapshot;
import com.timingjeju.api.application.accommodation.AccommodationIdentityGenerator;
import com.timingjeju.api.application.accommodation.AccommodationMutation;
import com.timingjeju.api.application.accommodation.AccommodationMutationPayload;
import com.timingjeju.api.application.accommodation.AccommodationPatchRecord;
import com.timingjeju.api.application.accommodation.AccommodationPatchValue;
import com.timingjeju.api.application.accommodation.AccommodationStore;
import com.timingjeju.api.application.accommodation.CreateAccommodationCommand;
import com.timingjeju.api.application.accommodation.PatchAccommodationCommand;
import com.timingjeju.api.application.trip.TripExpectedRevision;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

public class AccommodationService {
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[!-~]{1,128}$");

  private final AccommodationStore store;
  private final AccommodationIdentityGenerator identities;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public AccommodationService(
      AccommodationStore store,
      AccommodationIdentityGenerator identities,
      Clock clock,
      ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store);
    this.identities = Objects.requireNonNull(identities);
    this.clock = Objects.requireNonNull(clock);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Transactional
  public AccommodationHttpResult create(
      UUID ownerId,
      UUID tripId,
      String key,
      TripExpectedRevision expected,
      CreateAccommodationCommand command) {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(expected);
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw AccommodationException.invalidRequest();
    }
    CreateAccommodationCommand canonical = canonicalize(command);
    validate(canonical);
    String requestHash = requestHash(canonical);
    AccommodationCreateStoreResult result =
        store.create(
            new AccommodationCreateRecord(
                ownerId,
                tripId,
                key,
                requestHash,
                expected,
                identities.generate(),
                canonical,
                clock.instant()));
    if (result.replaySnapshot() != null) {
      return new AccommodationHttpResult(result.replaySnapshot(), true);
    }
    AccommodationMutationPayload payload = AccommodationMutationPayload.from(result.mutation());
    AccommodationHttpSnapshot snapshot =
        new AccommodationHttpSnapshot(
            201,
            "application/json",
            "/api/v1/trips/"
                + tripId
                + "/accommodations/"
                + result.mutation().accommodation().accommodationId(),
            payload.etag(),
            objectMapper.writeValueAsBytes(payload));
    store.completeCreateSnapshot(ownerId, tripId, key, snapshot);
    return new AccommodationHttpResult(snapshot, false);
  }

  @Transactional
  public AccommodationHttpResult patch(
      UUID ownerId,
      UUID tripId,
      UUID accommodationId,
      TripExpectedRevision expected,
      PatchAccommodationCommand command) {
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(accommodationId);
    Objects.requireNonNull(expected);
    PatchAccommodationCommand canonical = canonicalize(command);
    validate(canonical);
    AccommodationMutation mutation =
        store.patch(
            new AccommodationPatchRecord(
                ownerId, tripId, accommodationId, expected, canonical, clock.instant()));
    AccommodationMutationPayload payload = AccommodationMutationPayload.from(mutation);
    return new AccommodationHttpResult(
        new AccommodationHttpSnapshot(
            200, "application/json", null, payload.etag(), objectMapper.writeValueAsBytes(payload)),
        false);
  }

  @Transactional
  public void delete(
      UUID ownerId, UUID tripId, UUID accommodationId, TripExpectedRevision expected) {
    store.delete(
        new AccommodationDeleteRecord(
            Objects.requireNonNull(ownerId),
            Objects.requireNonNull(tripId),
            Objects.requireNonNull(accommodationId),
            Objects.requireNonNull(expected),
            clock.instant()));
  }

  private static CreateAccommodationCommand canonicalize(CreateAccommodationCommand command) {
    Objects.requireNonNull(command);
    return new CreateAccommodationCommand(
        command.placeId(),
        normalizeName(command.customName()),
        command.checkInDate(),
        command.checkOutDate(),
        command.checkInTime(),
        command.checkOutTime());
  }

  private static PatchAccommodationCommand canonicalize(PatchAccommodationCommand command) {
    Objects.requireNonNull(command);
    AccommodationPatchValue<String> name = command.customName();
    if (name.present() && name.value() != null) {
      name = AccommodationPatchValue.present(normalizeName(name.value()));
    }
    return new PatchAccommodationCommand(
        command.placeId(),
        name,
        command.checkInDate(),
        command.checkOutDate(),
        command.checkInTime(),
        command.checkOutTime());
  }

  private static String normalizeName(String value) {
    if (value == null) return null;
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) <= 0x20) start++;
    while (end > start && value.charAt(end - 1) <= 0x20) end--;
    return Normalizer.normalize(value.substring(start, end), Normalizer.Form.NFC);
  }

  private static void validate(CreateAccommodationCommand command) {
    if ((command.placeId() == null) == (command.customName() == null)
        || !validName(command.customName())
        || command.checkInDate() == null
        || command.checkOutDate() == null
        || command.checkInTime() == null
        || command.checkOutTime() == null) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static void validate(PatchAccommodationCommand command) {
    if (command.emptyPatch()
        || (command.customName().present()
            && command.customName().value() != null
            && !validName(command.customName().value()))
        || presentNull(command.checkInDate())
        || presentNull(command.checkOutDate())
        || presentNull(command.checkInTime())
        || presentNull(command.checkOutTime())) {
      throw AccommodationException.invalidRequest();
    }
  }

  private static boolean validName(String value) {
    return value == null
        || (!value.isBlank()
            && value.codePointCount(0, value.length()) <= 100
            && Normalizer.isNormalized(value, Normalizer.Form.NFC)
            && value.chars().noneMatch(Character::isISOControl));
  }

  private static boolean presentNull(AccommodationPatchValue<?> value) {
    return value.present() && value.value() == null;
  }

  private static String requestHash(CreateAccommodationCommand command) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, Objects.toString(command.placeId(), ""));
      update(digest, Objects.toString(command.customName(), ""));
      update(digest, command.checkInDate().toString());
      update(digest, command.checkOutDate().toString());
      update(digest, command.checkInTime().toString());
      update(digest, command.checkOutTime().toString());
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", failure);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
