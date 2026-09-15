package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.commandinput.CommandInputCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

/** 프로세스 재시작 이후에도 동일 작업·소유자의 입력만 복원하는 닫힌 저장 envelope다. */
public final class GenerationTripSnapshot {
  private final UUID runId;
  private final UUID ownerId;
  private final GenerationTripInput input;
  private final String canonicalInput;
  private final String inputHash;

  private GenerationTripSnapshot(
      UUID runId,
      UUID ownerId,
      GenerationTripInput input,
      String canonicalInput,
      String inputHash) {
    this.runId = runId;
    this.ownerId = ownerId;
    this.input = input;
    this.canonicalInput = canonicalInput;
    this.inputHash = inputHash;
  }

  public static GenerationTripSnapshot create(
      UUID runId, UUID ownerId, GenerationTripInput input, ObjectMapper mapper) {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(ownerId);
    Objects.requireNonNull(input);
    var canonicalizer = new CommandInputCanonicalizer(mapper);
    var tree = mapper.valueToTree(input);
    String canonical = canonicalizer.canonicalJson(tree);
    var envelope = mapper.createObjectNode();
    envelope
        .put("schemaVersion", 1)
        .put("runId", runId.toString())
        .put("ownerId", ownerId.toString());
    envelope.set("tripInput", tree);
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          canonicalizer.canonicalJson(envelope).getBytes(StandardCharsets.UTF_8)));
      return new GenerationTripSnapshot(runId, ownerId, input, canonical, hash);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static GenerationTripSnapshot restore(
      UUID runId, UUID ownerId, String storedInput, String expectedHash, ObjectMapper mapper) {
    try {
      if (storedInput == null
          || storedInput.length() > 262144
          || expectedHash == null
          || !expectedHash.matches("[0-9a-f]{64}"))
        throw GenerationException.inputConstraintViolation();
      var tree = mapper.readTree(storedInput);
      GenerationTripInput input =
          mapper
              .readerFor(GenerationTripInput.class)
              .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
              .readValue(storedInput);
      var restored = create(runId, ownerId, input, mapper);
      if (!new CommandInputCanonicalizer(mapper)
              .canonicalJson(tree)
              .equals(restored.canonicalInput())
          || !MessageDigest.isEqual(
              expectedHash.getBytes(StandardCharsets.US_ASCII),
              restored.inputHash().getBytes(StandardCharsets.US_ASCII)))
        throw GenerationException.inputConstraintViolation();
      return restored;
    } catch (RuntimeException failure) {
      throw GenerationException.inputConstraintViolation();
    }
  }

  public UUID runId() {
    return runId;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public GenerationTripInput input() {
    return input;
  }

  public String canonicalInput() {
    return canonicalInput;
  }

  public String inputHash() {
    return inputHash;
  }
}
