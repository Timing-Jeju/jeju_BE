package com.timingjeju.api.application.mobility;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class MobilityRouteCacheService {
  private static final String WALK_FALLBACK_SOURCE = "conservative-walk-policy";

  private final MobilityRouteProvider provider;
  private final ConservativeWalkEstimator walkEstimator;
  private final Clock clock;
  private final String sourceId;
  private final ConcurrentHashMap<CacheKey, MobilityRouteFact> cache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<CacheKey, CompletableFuture<MobilityRouteFact>> inFlight =
      new ConcurrentHashMap<>();

  public MobilityRouteCacheService(
      MobilityRouteProvider provider, ConservativeWalkEstimator walkEstimator, Clock clock) {
    this.provider = Objects.requireNonNull(provider, "provider는 필수입니다.");
    this.walkEstimator = Objects.requireNonNull(walkEstimator, "walkEstimator는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.sourceId = MobilityRouteRequestHasher.requireSourceId(provider.sourceId());
  }

  public MobilityRouteFact get(MobilityRouteRequest request) {
    Objects.requireNonNull(request, "request는 필수입니다.");
    String requestHash = MobilityRouteRequestHasher.hash(sourceId, request);
    CacheKey key = new CacheKey(sourceId, requestHash);
    MobilityRouteFact cached = cache.get(key);
    Instant now = clock.instant();
    if (cached != null && now.isBefore(cached.expiresAt())) return cached;
    if (cached != null) cache.remove(key, cached);
    return loadSingleFlight(key, request);
  }

  int inFlightCount() {
    return inFlight.size();
  }

  int cleanup() {
    Instant now = clock.instant();
    int before = cache.size();
    cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    return before - cache.size();
  }

  private MobilityRouteFact loadSingleFlight(CacheKey key, MobilityRouteRequest request) {
    CompletableFuture<MobilityRouteFact> leader = new CompletableFuture<>();
    CompletableFuture<MobilityRouteFact> active = inFlight.putIfAbsent(key, leader);
    if (active != null) return join(active);

    try {
      MobilityRouteFact loaded = load(key.requestHash(), request);
      cache.put(key, loaded);
      leader.complete(loaded);
      return loaded;
    } catch (RuntimeException failure) {
      leader.completeExceptionally(failure);
      throw failure;
    } finally {
      inFlight.remove(key, leader);
    }
  }

  private MobilityRouteFact load(String requestHash, MobilityRouteRequest request) {
    try {
      MobilityRouteMeasurement measurement = provider.fetch(request);
      if (measurement == null) throw MobilityRouteException.invalidProviderResponse();
      return normalize(requestHash, sourceId, request, measurement, false);
    } catch (MobilityRouteException failure) {
      if (!allowsWalkFallback(request, failure)) throw failure;
      return fallback(requestHash, request);
    } catch (RuntimeException failure) {
      MobilityRouteException sanitized = MobilityRouteException.providerUnavailable();
      if (!allowsWalkFallback(request, sanitized)) throw sanitized;
      return fallback(requestHash, request);
    }
  }

  private MobilityRouteFact fallback(String requestHash, MobilityRouteRequest request) {
    MobilityRouteMeasurement estimated;
    try {
      estimated =
          Objects.requireNonNull(walkEstimator.estimate(request), "walk estimator result는 필수입니다.");
    } catch (RuntimeException failure) {
      throw MobilityRouteException.externalFactsUnavailable();
    }
    return normalize(requestHash, WALK_FALLBACK_SOURCE, request, estimated, true);
  }

  private MobilityRouteFact normalize(
      String requestHash,
      String resultSourceId,
      MobilityRouteRequest request,
      MobilityRouteMeasurement measurement,
      boolean estimated) {
    if (measurement.mode() != request.mode()) {
      throw MobilityRouteException.invalidProviderResponse();
    }
    Instant observedAt = clock.instant();
    Instant expiresAt;
    try {
      expiresAt = observedAt.plus(measurement.validFor());
    } catch (RuntimeException failure) {
      throw MobilityRouteException.invalidProviderResponse();
    }
    return new MobilityRouteFact(
        requestHash,
        resultSourceId,
        request.mode(),
        measurement.distanceMeters(),
        measurement.duration(),
        measurement.fareKrw(),
        observedAt,
        expiresAt,
        false,
        estimated,
        estimated ? MobilityRouteReason.ESTIMATED_WALK_TIME : MobilityRouteReason.PROVIDER_FACT);
  }

  private static boolean allowsWalkFallback(
      MobilityRouteRequest request, MobilityRouteException failure) {
    return request.mode() == MobilityMode.WALK && failure.recoverable();
  }

  private static MobilityRouteFact join(CompletableFuture<MobilityRouteFact> future) {
    try {
      return future.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw MobilityRouteException.providerUnavailable();
    }
  }

  private record CacheKey(String sourceId, String requestHash) {}
}
