package com.timingjeju.api.domain.schedule.controller;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import com.timingjeju.api.application.idempotency.IdempotencyHeader;
import com.timingjeju.api.application.idempotency.IdempotencyRequest;
import com.timingjeju.api.application.idempotency.IdempotencyResponse;
import com.timingjeju.api.application.idempotency.IdempotencyUseCase;
import com.timingjeju.api.application.schedule.DeleteScheduleItemCommand;
import com.timingjeju.api.application.schedule.ScheduleException;
import com.timingjeju.api.application.schedule.service.ScheduleMutationService;
import com.timingjeju.api.application.security.CurrentUser;
import com.timingjeju.api.application.security.CurrentUserAccessor;
import com.timingjeju.api.application.trip.TripEntityTag;
import com.timingjeju.api.domain.schedule.controller.docs.ScheduleMutationApiDocs;
import com.timingjeju.api.domain.schedule.dto.CreateScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.MoveScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.PatchScheduleItemRequest;
import com.timingjeju.api.domain.schedule.dto.ReorderScheduleRequest;
import com.timingjeju.api.domain.schedule.dto.ScheduleMutationResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/trips/{tripId}")
public final class ScheduleMutationController implements ScheduleMutationApiDocs {
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Set<String> NON_NULL_OPTIONAL_FIELDS =
      Set.of(
          "placeId",
          "accommodationId",
          "transportEventId",
          "title",
          "bufferAfterMinutes",
          "required");
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "expectedActiveScheduleVersionId",
          "dayNo",
          "sequenceNo",
          "itemType",
          "plannedStartAt",
          "stayMinutes");

  private final ScheduleMutationService schedules;
  private final CurrentUserAccessor currentUsers;
  private final IdempotencyUseCase idempotency;
  private final ObjectMapper objectMapper;
  private final CommandInputCanonicalizer canonicalizer;
  private final ObjectReader createReader;
  private final ObjectReader patchReader;
  private final ObjectReader reorderReader;
  private final ObjectReader moveReader;

  public ScheduleMutationController(
      ScheduleMutationService schedules,
      CurrentUserAccessor currentUsers,
      IdempotencyUseCase idempotency,
      ObjectMapper objectMapper,
      CommandInputCanonicalizer canonicalizer) {
    this.schedules = schedules;
    this.currentUsers = currentUsers;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
    this.canonicalizer = canonicalizer;
    this.createReader =
        objectMapper
            .readerFor(CreateScheduleItemRequest.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    this.patchReader = strict(PatchScheduleItemRequest.class);
    this.reorderReader = strict(ReorderScheduleRequest.class);
    this.moveReader = strict(MoveScheduleItemRequest.class);
  }

  @Override
  @PostMapping("/schedule-items")
  public ResponseEntity<byte[]> addItem(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body,
      HttpServletRequest servletRequest) {
    if (!servletRequest.getParameterMap().isEmpty()) {
      throw ScheduleException.invalidRequest();
    }
    validateBodyFraming(servletRequest, body);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    var expectedTrip = TripEntityTag.parse(ifMatch);
    CurrentUser user = currentUsers.getRequired();
    validateBodySize(body);
    IdempotencyRequest request =
        IdempotencyRequest.create(
            user.userId(),
            "POST",
            "/api/v1/trips/" + canonicalTripId + "/schedule-items",
            idempotencyKey,
            body);
    AtomicBoolean replayed = new AtomicBoolean(true);
    IdempotencyResponse result =
        idempotency.execute(
            request,
            () -> {
              replayed.set(false);
              var mutation =
                  schedules.addItem(
                      user, canonicalTripId, expectedTrip, parseRequest(body).toCommand());
              ScheduleMutationResponse response = ScheduleMutationResponse.from(mutation);
              return new IdempotencyResponse(
                  201,
                  List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, response.etag())),
                  serialize(response));
            });
    ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    response.header("Idempotency-Replayed", Boolean.toString(replayed.get()));
    return response.body(result.body());
  }

  private CreateScheduleItemRequest parseRequest(byte[] body) {
    validateBodySize(body);
    try {
      CreateScheduleItemRequest request = createReader.readValue(body);
      JsonNode tree = objectMapper.readTree(body);
      if (tree == null
          || !tree.isObject()
          || REQUIRED_FIELDS.stream().anyMatch(name -> !tree.has(name) || tree.get(name).isNull())
          || NON_NULL_OPTIONAL_FIELDS.stream()
              .anyMatch(name -> tree.has(name) && tree.get(name).isNull())) {
        throw ScheduleException.invalidRequest();
      }
      validateCanonicalUuidFields(
          tree,
          Set.of(
              "expectedActiveScheduleVersionId", "placeId", "accommodationId", "transportEventId"));
      return request;
    } catch (JacksonException failure) {
      throw ScheduleException.invalidRequest();
    }
  }

  @PatchMapping("/schedule-items/{itemId}")
  @Override
  public ResponseEntity<byte[]> patchItem(
      @PathVariable String tripId,
      @PathVariable String itemId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body,
      HttpServletRequest servletRequest) {
    validateNoParameters(servletRequest);
    validateBodyFraming(servletRequest, body);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalItemId = parseCanonicalUuid(itemId);
    PatchScheduleItemRequest request = read(body, patchReader, PatchScheduleItemRequest.class);
    JsonNode tree = readTree(body);
    requireFields(tree, Set.of("expectedActiveScheduleVersionId"));
    validateCanonicalUuidFields(
        tree,
        Set.of(
            "expectedActiveScheduleVersionId", "placeId", "accommodationId", "transportEventId"));
    rejectNullFields(
        tree,
        Set.of(
            "placeId",
            "accommodationId",
            "transportEventId",
            "title",
            "plannedStartAt",
            "stayMinutes",
            "bufferAfterMinutes",
            "required"));
    Set<String> present = new java.util.HashSet<>();
    tree.properties()
        .forEach(
            property -> {
              String name = property.getKey();
              if (!"expectedActiveScheduleVersionId".equals(name)) present.add(name);
            });
    return executeMutation(
        "PATCH",
        "/api/v1/trips/" + canonicalTripId + "/schedule-items/" + canonicalItemId,
        idempotencyKey,
        body,
        ifMatch,
        (user, expected) ->
            schedules.patchItem(
                user, canonicalTripId, canonicalItemId, expected, request.toCommand(present)),
        200);
  }

  @DeleteMapping("/schedule-items/{itemId}")
  @Override
  public ResponseEntity<byte[]> deleteItem(
      @PathVariable String tripId,
      @PathVariable String itemId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestParam(name = "expectedActiveScheduleVersionId", required = false)
          String expectedActiveScheduleVersionId,
      HttpServletRequest servletRequest) {
    validateDeleteFraming(servletRequest);
    if (!servletRequest.getParameterMap().keySet().equals(Set.of("expectedActiveScheduleVersionId"))
        || servletRequest.getParameterValues("expectedActiveScheduleVersionId").length != 1) {
      throw ScheduleException.invalidRequest();
    }
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalItemId = parseCanonicalUuid(itemId);
    UUID expectedVersion = parseCanonicalUuid(expectedActiveScheduleVersionId);
    byte[] canonicalPayload =
        ("{\"expectedActiveScheduleVersionId\":\"" + expectedVersion + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    return executeMutation(
        "DELETE",
        "/api/v1/trips/" + canonicalTripId + "/schedule-items/" + canonicalItemId,
        idempotencyKey,
        canonicalPayload,
        ifMatch,
        (user, expected) ->
            schedules.deleteItem(
                user,
                canonicalTripId,
                canonicalItemId,
                expected,
                new DeleteScheduleItemCommand(expectedVersion)),
        200);
  }

  @PutMapping("/schedule-order")
  @Override
  public ResponseEntity<byte[]> reorder(
      @PathVariable String tripId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body,
      HttpServletRequest servletRequest) {
    validateNoParameters(servletRequest);
    validateBodyFraming(servletRequest, body);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    ReorderScheduleRequest request = read(body, reorderReader, ReorderScheduleRequest.class);
    JsonNode tree = readTree(body);
    requireFields(tree, Set.of("expectedActiveScheduleVersionId", "days"));
    validateCanonicalUuidFields(tree, Set.of("expectedActiveScheduleVersionId", "orderedItemIds"));
    return executeMutation(
        "PUT",
        "/api/v1/trips/" + canonicalTripId + "/schedule-order",
        idempotencyKey,
        body,
        ifMatch,
        (user, expected) -> schedules.reorder(user, canonicalTripId, expected, request.toCommand()),
        200);
  }

  @PostMapping("/schedule-items/{itemId}/move")
  @Override
  public ResponseEntity<byte[]> moveItem(
      @PathVariable String tripId,
      @PathVariable String itemId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody byte[] body,
      HttpServletRequest servletRequest) {
    validateNoParameters(servletRequest);
    validateBodyFraming(servletRequest, body);
    UUID canonicalTripId = parseCanonicalUuid(tripId);
    UUID canonicalItemId = parseCanonicalUuid(itemId);
    MoveScheduleItemRequest request = read(body, moveReader, MoveScheduleItemRequest.class);
    JsonNode tree = readTree(body);
    requireFields(
        tree,
        Set.of(
            "expectedActiveScheduleVersionId",
            "targetDayNo",
            "targetSequenceNo",
            "plannedStartAt"));
    validateCanonicalUuidFields(tree, Set.of("expectedActiveScheduleVersionId"));
    return executeMutation(
        "POST",
        "/api/v1/trips/" + canonicalTripId + "/schedule-items/" + canonicalItemId + "/move",
        idempotencyKey,
        body,
        ifMatch,
        (user, expected) ->
            schedules.moveItem(
                user, canonicalTripId, canonicalItemId, expected, request.toCommand()),
        200);
  }

  private ResponseEntity<byte[]> executeMutation(
      String method,
      String path,
      String key,
      byte[] body,
      String ifMatch,
      Mutation operation,
      int successStatus) {
    validateBodySize(body);
    CurrentUser user = currentUsers.getRequired();
    var expected = TripEntityTag.parse(ifMatch);
    byte[] hashBody =
        method.equals("DELETE")
            ? body
            : canonicalizer.canonicalJson(readTree(body)).getBytes(StandardCharsets.UTF_8);
    IdempotencyRequest request =
        IdempotencyRequest.create(user.userId(), method, path, key, hashBody);
    AtomicBoolean replayed = new AtomicBoolean(true);
    IdempotencyResponse result =
        idempotency.execute(
            request,
            () -> {
              replayed.set(false);
              ScheduleMutationResponse response =
                  ScheduleMutationResponse.from(operation.execute(user, expected));
              return new IdempotencyResponse(
                  successStatus,
                  List.of(
                      new IdempotencyHeader(
                          HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
                      new IdempotencyHeader(HttpHeaders.ETAG, response.etag())),
                  serialize(response));
            });
    ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
    result.headers().forEach(header -> response.header(header.name(), header.value()));
    response.header("Idempotency-Replayed", Boolean.toString(replayed.get()));
    return response.body(result.body());
  }

  private <T> T read(byte[] body, ObjectReader reader, Class<T> type) {
    validateBodySize(body);
    try {
      return reader.readValue(body);
    } catch (JacksonException failure) {
      throw ScheduleException.invalidRequest();
    }
  }

  private JsonNode readTree(byte[] body) {
    try {
      JsonNode tree = objectMapper.readTree(body);
      if (tree == null || !tree.isObject()) throw ScheduleException.invalidRequest();
      return tree;
    } catch (JacksonException failure) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static void requireFields(JsonNode tree, Set<String> fields) {
    if (fields.stream().anyMatch(name -> !tree.has(name) || tree.get(name).isNull())) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static void rejectNullFields(JsonNode tree, Set<String> fields) {
    if (fields.stream().anyMatch(name -> tree.has(name) && tree.get(name).isNull())) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static void validateCanonicalUuidFields(JsonNode node, Set<String> names) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              property -> {
                String name = property.getKey();
                JsonNode value = property.getValue();
                if (names.contains(name)) {
                  if (value.isArray()) {
                    value.forEach(child -> validateCanonicalUuidValue(child));
                  } else if (!value.isNull()) {
                    validateCanonicalUuidValue(value);
                  }
                }
                validateCanonicalUuidFields(value, names);
              });
    } else if (node.isArray()) {
      node.forEach(child -> validateCanonicalUuidFields(child, names));
    }
  }

  private static void validateCanonicalUuidValue(JsonNode value) {
    if (!value.isTextual()) throw ScheduleException.invalidRequest();
    parseCanonicalUuid(value.asText());
  }

  private ObjectReader strict(Class<?> type) {
    return objectMapper.readerFor(type).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  private static void validateNoParameters(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) throw ScheduleException.invalidRequest();
  }

  private static void validateDeleteFraming(HttpServletRequest request) {
    Enumeration<String> encodings = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    if ((encodings != null && encodings.hasMoreElements())
        || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
      throw ScheduleException.invalidRequest();
    }
    Enumeration<String> lengths = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    int count = 0;
    while (lengths != null && lengths.hasMoreElements()) {
      count++;
      if (count > 1 || !"0".equals(lengths.nextElement())) throw ScheduleException.invalidRequest();
    }
    try {
      if (request.getInputStream().read() != -1) throw ScheduleException.invalidRequest();
    } catch (IOException failure) {
      throw ScheduleException.invalidRequest();
    }
  }

  private static void validateBodyFraming(HttpServletRequest request, byte[] body) {
    Enumeration<String> encodings = request.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    if ((encodings != null && encodings.hasMoreElements())
        || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
      throw ScheduleException.invalidRequest();
    }
    Enumeration<String> lengths = request.getHeaders(HttpHeaders.CONTENT_LENGTH);
    int count = 0;
    long declared = -1;
    while (lengths != null && lengths.hasMoreElements()) {
      count++;
      String value = lengths.nextElement();
      if (count > 1 || value.indexOf(',') >= 0) throw ScheduleException.invalidRequest();
      try {
        declared = Long.parseLong(value);
      } catch (NumberFormatException failure) {
        throw ScheduleException.invalidRequest();
      }
    }
    if (count != 1
        || declared != body.length
        || declared < 0
        || request.getContentLengthLong() != declared) {
      throw ScheduleException.invalidRequest();
    }
  }

  @FunctionalInterface
  private interface Mutation {
    com.timingjeju.api.application.schedule.ScheduleMutationResult execute(
        CurrentUser user, com.timingjeju.api.application.trip.TripExpectedRevision expected);
  }

  private static void validateBodySize(byte[] body) {
    if (body == null || body.length > IdempotencyRequest.MAX_BODY_BYTES) {
      throw ScheduleException.invalidRequest();
    }
  }

  private byte[] serialize(ScheduleMutationResponse response) {
    try {
      return objectMapper.writeValueAsBytes(response);
    } catch (JacksonException failure) {
      throw ScheduleException.internalServerError();
    }
  }

  private static UUID parseCanonicalUuid(String raw) {
    if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
      throw ScheduleException.invalidRequest();
    }
    try {
      UUID parsed = UUID.fromString(raw);
      if (!parsed.toString().equals(raw)) {
        throw ScheduleException.invalidRequest();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw ScheduleException.invalidRequest();
    }
  }
}
