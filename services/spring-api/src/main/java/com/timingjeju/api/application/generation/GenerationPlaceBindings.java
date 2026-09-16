package com.timingjeju.api.application.generation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 승인된 TourAPI 및 KAC 공항 canonical 조회 결과의 요청 단위 매핑. */
public final class GenerationPlaceBindings {
  private final Map<UUID, Place> byCanonical;
  private final Map<String, UUID> byFact;

  public GenerationPlaceBindings(List<Place> places) {
    var canonical = new HashMap<UUID, Place>();
    var facts = new HashMap<String, UUID>();
    for (var place : places) {
      if (canonical.putIfAbsent(place.canonicalId(), place) != null
          || facts.putIfAbsent(place.factId(), place.canonicalId()) != null)
        throw GenerationException.inputUnavailable();
    }
    byCanonical = Map.copyOf(canonical);
    byFact = Map.copyOf(facts);
  }

  public String factId(UUID canonicalId) {
    return require(canonicalId).factId();
  }

  public UUID canonicalId(String factId) {
    var id = byFact.get(factId);
    if (id == null) throw GenerationException.inputUnavailable();
    return id;
  }

  public Set<String> factIds() {
    return byFact.keySet();
  }

  public Map<String, Object> reference(UUID canonicalId) {
    return Map.of("place_id", factId(canonicalId));
  }

  public Map<String, Object> accommodation(UUID canonicalId) {
    var place = require(canonicalId);
    return Map.of("place_id", place.factId(), "name", place.officialName());
  }

  private Place require(UUID id) {
    var place = byCanonical.get(id);
    if (place == null) throw GenerationException.inputUnavailable();
    return place;
  }

  public static boolean approvedFactId(String id) {
    return id != null
        && (id.matches("tourapi\\.place:[0-9]{1,32}") || id.equals("kac.airport:CJU"));
  }

  public static String sourceForFactId(String id) {
    if (!approvedFactId(id)) throw GenerationException.inputUnavailable();
    return id.substring(0, id.indexOf(':'));
  }

  public record Place(UUID canonicalId, String contentId, String officialName, String sourceId) {
    public Place(UUID canonicalId, String contentId, String officialName) {
      this(canonicalId, contentId, officialName, "tourapi.place");
    }

    public Place {
      if (canonicalId == null
          || contentId == null
          || !approvedFactId(sourceId + ":" + contentId)
          || officialName == null
          || officialName.isBlank()) throw GenerationException.inputUnavailable();
    }

    public String factId() {
      // AI의 승인된 source별 publication identity만 사용한다.
      return sourceId + ":" + contentId;
    }
  }
}
