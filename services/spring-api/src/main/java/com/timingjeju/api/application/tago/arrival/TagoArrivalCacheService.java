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
  private final Clock clock;
  private final Duration freshTtl;
  private final Duration staleWindow;
  private final ConcurrentHashMap<TagoArrivalCacheKey, TagoArrivalSnapshot> cache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<TagoArrivalCacheKey, CompletableFuture<TagoArrivalSnapshot>>
      inFlight = new ConcurrentHashMap<>();

  public TagoArrivalCacheService(
      TagoArrivalLoader loader, Clock clock, Duration freshTtl, Duration staleWindow) {
    this(loader, key -> java.util.Optional.empty(), clock, freshTtl, staleWindow);
  }

  public TagoArrivalCacheService(
      TagoArrivalLoader loader,
      TagoArrivalHistory history,
      Clock clock,
      Duration freshTtl,
      Duration staleWindow) {
    this.loader = Objects.requireNonNull(loader, "loader는 필수입니다.");
    this.history = Objects.requireNonNull(history, "history는 필수입니다.");
    this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
    this.freshTtl = requireDuration(freshTtl, Duration.ofSeconds(20), Duration.ofSeconds(30));
    this.staleWindow = requireDuration(staleWindow, Duration.ofMinutes(2), Duration.ofMinutes(2));
  }

  public TagoArrivalSnapshot get(TagoArrivalCacheKey key) {
    Objects.requireNonNull(key, "key는 필수입니다.");
    Instant now = clock.instant();
    TagoArrivalSnapshot cached = cache.get(key);
    if (cached == null) {
      cached = history.findLatest(key).orElse(null);
      if (cached != null) cache.putIfAbsent(key, cached);
    }
    if (cached != null && now.isBefore(cached.expiresAt())) return cached;

    try {
      return loadSingleFlight(key);
    } catch (TagoArrivalException failure) {
      TagoArrivalSnapshot fallback = cache.get(key);
      if (fallback != null && !now.isAfter(fallback.observedAt().plus(staleWindow))) {
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
      TagoArrivalSnapshot loaded =
          Objects.requireNonNull(loader.load(key), "loader result는 필수입니다.");
      if (!loaded.expiresAt().equals(loaded.observedAt().plus(freshTtl)) || loaded.stale()) {
        throw TagoArrivalException.invalidResponse();
      }
      cache.put(key, loaded);
      leader.complete(loaded);
      return loaded;
    } catch (RuntimeException failure) {
      leader.completeExceptionally(failure);
      throw classify(failure);
    } finally {
      inFlight.remove(key, leader);
    }
  }

  private static TagoArrivalSnapshot join(CompletableFuture<TagoArrivalSnapshot> future) {
    try {
      return future.join();
    } catch (CompletionException failure) {
      throw classify(failure.getCause());
    }
  }

  private static TagoArrivalException classify(Throwable failure) {
    if (failure instanceof TagoArrivalException arrivalFailure) return arrivalFailure;
    return TagoArrivalException.providerUnavailable();
  }

  private static Duration requireDuration(Duration value, Duration minimum, Duration maximum) {
    Objects.requireNonNull(value, "duration은 필수입니다.");
    if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("cache duration이 허용 범위를 벗어났습니다.");
    }
    return value;
  }
}
