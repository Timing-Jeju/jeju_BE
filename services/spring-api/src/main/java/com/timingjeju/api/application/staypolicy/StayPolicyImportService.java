package com.timingjeju.api.application.staypolicy;

import com.timingjeju.api.domain.places.model.CanonicalPlaceCategory;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class StayPolicyImportService {

  private static final Pattern VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final int MIN_MINUTES = 5;
  private static final int MAX_MINUTES = 1_440;
  private static final int MAX_POLICIES = 10_000;

  private final StayPolicyTargetCatalog targetCatalog;
  private final StayPolicyPublicationStore publicationStore;
  private final Clock clock;
  private final StayPolicyPayloadHasher hasher = new StayPolicyPayloadHasher();

  public StayPolicyImportService(
      StayPolicyTargetCatalog targetCatalog,
      StayPolicyPublicationStore publicationStore,
      Clock clock) {
    this.targetCatalog = targetCatalog;
    this.publicationStore = publicationStore;
    this.clock = clock;
  }

  public StayPolicyImportResult importPolicy(StayPolicyPayload payload, boolean dryRun) {
    Instant now = clock.instant();
    StayPolicyPayload normalized = normalize(payload);
    List<String> violations = validateShape(normalized, now);
    if (normalized == null || normalized.policies() == null) {
      throwIfInvalid(violations);
      throw new IllegalStateException("unreachable");
    }

    Set<String> categories = new HashSet<>();
    Set<UUID> placeIds = new HashSet<>();
    for (StayPolicyCandidate policy : normalized.policies()) {
      if (policy == null || policy.scope() == null) {
        continue;
      }
      if (policy.scope() == StayPolicyScope.CATEGORY_DEFAULT && policy.category() != null) {
        categories.add(policy.category());
      }
      if (policy.scope() == StayPolicyScope.PLACE_OVERRIDE && policy.placeId() != null) {
        placeIds.add(policy.placeId());
      }
    }
    throwIfInvalid(violations);

    if (dryRun) {
      StayPolicyTargetValidation targets = targetCatalog.validateTargets(categories, placeIds);
      categories.stream()
          .filter(category -> !targets.liveCategories().contains(category))
          .sorted()
          .forEach(category -> violations.add("unknown canonical category: " + category));
      placeIds.stream()
          .filter(placeId -> !targets.livePlaceIds().contains(placeId))
          .sorted()
          .forEach(placeId -> violations.add("missing, stale or tombstoned place: " + placeId));
      throwIfInvalid(violations);
    }

    String payloadHash = hasher.hash(normalized);
    ValidatedStayPolicyPayload validated =
        new ValidatedStayPolicyPayload(
            normalized.version(),
            normalized.effectiveAt(),
            normalized.expectedActiveVersion(),
            payloadHash,
            normalized.policies());
    if (!dryRun) {
      publicationStore.publish(validated, now);
    }
    return new StayPolicyImportResult(
        normalized.version(), payloadHash, normalized.policies().size(), dryRun);
  }

  private static StayPolicyPayload normalize(StayPolicyPayload payload) {
    if (payload == null || payload.policies() == null) {
      return payload;
    }
    List<StayPolicyCandidate> policies =
        payload.policies().stream()
            .map(
                policy -> {
                  if (policy == null || policy.category() == null) {
                    return policy;
                  }
                  String category = Normalizer.normalize(policy.category(), Normalizer.Form.NFC);
                  return new StayPolicyCandidate(
                      policy.scope(), category, policy.placeId(), policy.minutes());
                })
            .toList();
    return new StayPolicyPayload(
        normalizeIdentifier(payload.version()),
        payload.effectiveAt(),
        normalizeIdentifier(payload.expectedActiveVersion()),
        policies);
  }

  private static String normalizeIdentifier(String value) {
    return value == null ? null : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
  }

  private static List<String> validateShape(StayPolicyPayload payload, Instant now) {
    List<String> violations = new ArrayList<>();
    if (payload == null) {
      violations.add("payload is required");
      return violations;
    }
    if (payload.version() == null || !VERSION.matcher(payload.version()).matches()) {
      violations.add("version must be a lowercase stable identifier");
    }
    if (payload.effectiveAt() == null) {
      violations.add("effectiveAt is required");
    } else if (payload.effectiveAt().isAfter(now)) {
      violations.add("effectiveAt must not be in the future");
    }
    if (payload.expectedActiveVersion() != null
        && !VERSION.matcher(payload.expectedActiveVersion()).matches()) {
      violations.add("expectedActiveVersion must be a lowercase stable identifier");
    }
    if (payload.policies() == null || payload.policies().isEmpty()) {
      violations.add("policies must not be empty");
      return violations;
    }
    if (payload.policies().size() > MAX_POLICIES) {
      violations.add("policies must contain at most " + MAX_POLICIES + " rows");
    }
    Set<String> targets = new HashSet<>();
    for (int index = 0; index < payload.policies().size(); index++) {
      StayPolicyCandidate policy = payload.policies().get(index);
      if (policy == null || policy.scope() == null) {
        violations.add("policy[" + index + "] scope is required");
        continue;
      }
      boolean exactScope =
          switch (policy.scope()) {
            case CATEGORY_DEFAULT -> policy.category() != null && policy.placeId() == null;
            case PLACE_OVERRIDE -> policy.category() == null && policy.placeId() != null;
          };
      if (!exactScope) {
        violations.add("policy[" + index + "] category/place scope must be exactly one");
      }
      if (policy.scope() == StayPolicyScope.CATEGORY_DEFAULT
          && policy.category() != null
          && !CanonicalPlaceCategory.isValid(policy.category())) {
        violations.add("policy[" + index + "] category must be a canonical code");
      }
      if (policy.minutes() < MIN_MINUTES || policy.minutes() > MAX_MINUTES) {
        violations.add("policy[" + index + "] minutes must be between 5 and 1440");
      }
      if (exactScope && !targets.add(policy.targetKey())) {
        violations.add("duplicate policy target: " + policy.targetKey());
      }
    }
    return violations;
  }

  private static void throwIfInvalid(List<String> violations) {
    if (!violations.isEmpty()) {
      throw new StayPolicyValidationException(List.copyOf(violations));
    }
  }
}
