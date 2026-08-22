package com.timingjeju.api.application.tago.arrival;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class TagoArrivalCacheService {
  private final TagoArrivalLoader loader;
  private final TagoArrivalHistory history;
  private final TagoArrivalFlightCoordinator coordinator;
  private final Clock clock;
  private final Duration freshTtl;
  private final Duration staleWindow;
  private final ConcurrentHashMap<TagoArrivalCacheKey, TagoArrivalSnapshot> cache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<TagoArrivalCacheKey, CompletableFuture<TagoArrivalSnapshot>>
      inFlight = new ConcurrentHashMap<>();

  public TagoArrivalCacheService(
      TagoArrivalLoader loader, Clock clock, Duration freshTtl, Duration staleWindow) {
    this(
        loader,
        key -> java.util.Optional.empty(),
        (key, action) -> action.get(),
        clock,
        freshTtl,
        staleWindow);
  }

  public TagoArrivalCacheService(
      TagoArrivalLoader loader,
      TagoArrivalHistory history,
      Clock clock,
      Duration freshTtl,
      Duration staleWindow) {
    this(loader, history, (key, action) -> action.get(), clock, freshTtl, staleWindow);
  }

  public TagoArrivalCacheService(
      TagoArrivalLoader loader,
      TagoArrivalHistory history,
      TagoArrivalFlightCoordinator coordinator,
      Clock clock,
      Duration freshTtl,
      Duration staleWindow) {
    this.loader = Objects.requireNonNull(loader, "loader는 필수입니다.");
    this.history = Objects.requireNonNull(history, "history는 필수입니다.");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.freshTtl = requireDuration(freshTtl, Duration.ofSeconds(20), Duration.ofSeconds(30));
    this.staleWindow = requireDuration(staleWindow, Duration.ofMinutes(2), Duration.ofMinutes(2));
  }

  public TagoArrivalSnapshot get(TagoArrivalCacheKey key) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    TagoArrivalSnapshot cached = cache.get(key);
    if (cached == null) {
      cached = history.findLatest(key).orElse(null);
      if (cached != null) cache.putIfAbsent(key, cached);
    }
    Instant now = clock.instant();
    if (cached != null && now.isBefore(cached.expiresAt())) return cached;

    try {
      return loadSingleFlight(key);
    } catch (TagoArrivalException failure) {
      if (!allowsStaleFallback(failure.code())) throw failure;
      TagoArrivalSnapshot fallback = cache.get(key);
      Instant failedAt = clock.instant();
      if (fallback != null && !failedAt.isAfter(fallback.observedAt().plus(staleWindow))) {
        return fallback.asStale();
      }
      throw failure;
    }
  }

  public int cleanup() {
    Instant now = clock.instant();
    int before = cache.size();
    cache
        .entrySet()
        .removeIf(entry -> now.isAfter(entry.getValue().observedAt().plus(staleWindow)));
    return before - cache.size();
  }

  int inFlightCount() {
    return inFlight.size();
  }

  int cachedStopCount() {
    return cache.size();
  }

  private TagoArrivalSnapshot loadSingleFlight(TagoArrivalCacheKey key) {
    CompletableFuture<TagoArrivalSnapshot> leader = new CompletableFuture<>();
    CompletableFuture<TagoArrivalSnapshot> active = inFlight.putIfAbsent(key, leader);
    if (active != null) return join(active);

    try {
      TagoArrivalSnapshot coordinated =
          coordinator.coalesce(
              key, lease -> loadAfterClaim(key, lease), () -> replayAfterSuccess(key));
      leader.complete(coordinated);
      return coordinated;
    } catch (RuntimeException failure) {
      leader.completeExceptionally(failure);
      throw failure;
    } finally {
      inFlight.remove(key, leader);
    }
  }

  private TagoArrivalSnapshot loadAfterClaim(
      TagoArrivalCacheKey key, TagoArrivalFlightLease flight) {
    TagoArrivalSnapshot latest = history.findLatest(key).orElse(null);
    Instant now = clock.instant();
    if (latest != null) {
      cache.put(key, latest);
      if (now.isBefore(latest.expiresAt())) return latest;
    }

    TagoArrivalSnapshot loaded =
        Objects.requireNonNull(loader.load(key, flight), "loader result는 필수입니다.");
    if (!loaded.expiresAt().equals(loaded.observedAt().plus(freshTtl)) || loaded.stale()) {
      throw TagoArrivalException.invalidResponse();
    }
    cache.put(key, loaded);
    return loaded;
  }

  private TagoArrivalSnapshot replayAfterSuccess(TagoArrivalCacheKey key) {
    TagoArrivalSnapshot latest =
        history.findLatest(key).orElseThrow(TagoArrivalException::dataUnavailable);
    Instant now = clock.instant();
    if (!now.isBefore(latest.expiresAt())) throw TagoArrivalReplayExpiredException.create();
    cache.put(key, latest);
    return latest;
  }

  private static TagoArrivalSnapshot join(CompletableFuture<TagoArrivalSnapshot> future) {
    try {
      return future.join();
    } catch (CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException runtimeFailure) throw runtimeFailure;
      throw failure;
    }
  }

  private static boolean allowsStaleFallback(TagoArrivalException.Code code) {
    return switch (code) {
      case RATE_LIMITED, TIMEOUT, PROVIDER_UNAVAILABLE -> true;
      case INVALID_REQUEST, INVALID_PROVIDER_RESPONSE, EMPTY_RESULT, DATA_UNAVAILABLE -> false;
    };
  }

  private static Duration requireDuration(Duration value, Duration minimum, Duration maximum) {
    Objects.requireNonNull(value, "duration은 필수입니다.");
    if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("cache duration이 허용 범위를 벗어났습니다.");
    }
    return value;
  }
}
