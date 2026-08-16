package com.timingjeju.api.global.placestop;

import com.timingjeju.api.application.placestop.PlaceStopLinkBatch;
import com.timingjeju.api.application.placestop.PlaceStopLinkBatchResult;
import com.timingjeju.api.application.placestop.PlaceStopLinkCandidate;
import com.timingjeju.api.application.placestop.PlaceStopLinkConflictException;
import com.timingjeju.api.application.placestop.PlaceStopLinkPolicy;
import com.timingjeju.api.application.placestop.PlaceStopLinkRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlaceStopLinkRepository implements PlaceStopLinkRepository {

  private static final double JEJU_MIN_LONGITUDE = 125.0;
  private static final double JEJU_MAX_LONGITUDE = 127.5;
  private static final double JEJU_MIN_LATITUDE = 32.5;
  private static final double JEJU_MAX_LATITUDE = 34.0;
  private static final double WALK_METERS_PER_MINUTE = 80.0;

  private final JdbcTemplate jdbcTemplate;

  public JdbcPlaceStopLinkRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
  }

  @Override
  @Transactional
  public PlaceStopLinkBatchResult recompute(PlaceStopLinkBatch batch, PlaceStopLinkPolicy policy) {
    Objects.requireNonNull(batch, "batch는 필수입니다.");
    Objects.requireNonNull(policy, "policy는 필수입니다.");
    Set<UUID> scopes = resolveScopes(batch, policy.radiusMeters());
    int upserted = 0;
    int tombstoned = 0;
    int replayed = 0;
    for (UUID placeId : scopes.stream().sorted().toList()) {
      lockScope(placeId, batch.sourceProvider());
      if (isReplayOrThrow(placeId, batch)) {
        replayed++;
        continue;
      }
      PlaceCoordinate coordinate = requireJejuPlace(placeId);
      List<CandidateWrite> candidates = candidates(coordinate, batch, policy);
      for (CandidateWrite candidate : candidates) {
        upsert(placeId, candidate, batch);
        upserted++;
      }
      if (batch.complete()) {
        tombstoned += tombstoneMissing(placeId, candidates, batch);
        advanceScope(placeId, batch);
      }
    }
    return new PlaceStopLinkBatchResult(
        scopes.size(), upserted, tombstoned, !scopes.isEmpty() && replayed == scopes.size());
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlaceStopLinkCandidate> findEligible(
      UUID placeId, int radiusMeters, int maxCandidates, Instant now) {
    Objects.requireNonNull(placeId, "placeId는 필수입니다.");
    Objects.requireNonNull(now, "now는 필수입니다.");
    if (radiusMeters < 1 || radiusMeters > PlaceStopLinkPolicy.MAX_RADIUS_METERS) {
      throw new IllegalArgumentException("radiusMeters는 1 이상 500 이하여야 합니다.");
    }
    if (maxCandidates < 1 || maxCandidates > PlaceStopLinkPolicy.MAX_CANDIDATES) {
      throw new IllegalArgumentException("maxCandidates는 1 이상 100 이하여야 합니다.");
    }
    return jdbcTemplate.query(
        """
        select link.stop_id, link.distance_meters, link.walk_minutes, link.expires_at,
               link.expires_at > ? as fresh
        from public.place_stop_links link
        join public.bus_stops stop on stop.id=link.stop_id
        where link.place_id=? and link.enabled and link.tombstoned_at is null
          and stop.tombstoned_at is null and stop.source_deleted_at is null
          and link.distance_meters <= ?
        order by (link.expires_at > ?) desc, link.expires_at desc,
                 link.distance_meters, link.stop_id
        limit ?
        """,
        (resultSet, rowNumber) ->
            new PlaceStopLinkCandidate(
                resultSet.getObject("stop_id", UUID.class),
                resultSet.getInt("distance_meters"),
                resultSet.getInt("walk_minutes"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getBoolean("fresh")),
        Timestamp.from(now),
        placeId,
        radiusMeters,
        Timestamp.from(now),
        maxCandidates);
  }

  private Set<UUID> resolveScopes(PlaceStopLinkBatch batch, int radiusMeters) {
    Set<UUID> scopes = new LinkedHashSet<>(batch.changedPlaceIds());
    for (UUID stopId : batch.changedStopIds()) {
      scopes.addAll(
          jdbcTemplate.queryForList(
              "select place_id from public.place_stop_links where stop_id=?", UUID.class, stopId));
      scopes.addAll(
          jdbcTemplate.queryForList(
              """
              select place.id
              from public.tour_places place
              join public.bus_stops stop on stop.id=?
              where ST_DWithin(place.location, stop.location, ?)
              """,
              UUID.class,
              stopId,
              radiusMeters));
    }
    return scopes;
  }

  private void lockScope(UUID placeId, String sourceProvider) {
    jdbcTemplate.query(
        "select pg_advisory_xact_lock(hashtextextended(?::text, hashtextextended(?, 0)))",
        resultSet -> null,
        placeId,
        sourceProvider);
  }

  private boolean isReplayOrThrow(UUID placeId, PlaceStopLinkBatch batch) {
    List<ScopeState> states =
        jdbcTemplate.query(
            """
            select observed_at, manifest_fingerprint
            from public.place_stop_link_scope_states
            where place_id=? and source_provider=?
            for update
            """,
            (resultSet, rowNumber) ->
                new ScopeState(
                    resultSet.getTimestamp("observed_at").toInstant(),
                    resultSet.getString("manifest_fingerprint")),
            placeId,
            batch.sourceProvider());
    if (states.isEmpty()) return false;
    ScopeState state = states.getFirst();
    int comparison = state.observedAt().compareTo(batch.observedAt());
    if (comparison > 0 || (comparison == 0 && !state.fingerprint().equals(batch.fingerprint()))) {
      throw new PlaceStopLinkConflictException();
    }
    return comparison == 0;
  }

  private PlaceCoordinate requireJejuPlace(UUID placeId) {
    List<PlaceCoordinate> places =
        jdbcTemplate.query(
            "select ST_X(location::geometry) longitude, ST_Y(location::geometry) latitude from public.tour_places where id=? for update",
            (resultSet, rowNumber) ->
                new PlaceCoordinate(
                    resultSet.getDouble("longitude"), resultSet.getDouble("latitude")),
            placeId);
    if (places.isEmpty()) throw new IllegalArgumentException("존재하지 않는 place scope입니다.");
    PlaceCoordinate coordinate = places.getFirst();
    if (coordinate.longitude() < JEJU_MIN_LONGITUDE
        || coordinate.longitude() > JEJU_MAX_LONGITUDE
        || coordinate.latitude() < JEJU_MIN_LATITUDE
        || coordinate.latitude() > JEJU_MAX_LATITUDE) {
      throw new IllegalArgumentException("place 좌표가 제주 범위를 벗어났습니다.");
    }
    return coordinate;
  }

  private List<CandidateWrite> candidates(
      PlaceCoordinate coordinate, PlaceStopLinkBatch batch, PlaceStopLinkPolicy policy) {
    return jdbcTemplate.query(
        """
        select stop.id,
               ST_Distance(stop.location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) distance,
               least(?::timestamptz + (? * interval '1 millisecond'),
                     stop.last_seen_at + (? * interval '1 millisecond')) expires_at
        from public.bus_stops stop
        where stop.tombstoned_at is null and stop.source_deleted_at is null
          and stop.last_seen_at + (? * interval '1 millisecond') > ?::timestamptz
          and ST_DWithin(stop.location,
                         ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
        order by distance, stop.id
        limit ?
        """,
        (resultSet, rowNumber) -> {
          double distance = resultSet.getDouble("distance");
          return new CandidateWrite(
              resultSet.getObject("id", UUID.class),
              (int) Math.round(distance),
              (int) Math.ceil(distance / WALK_METERS_PER_MINUTE),
              resultSet.getTimestamp("expires_at").toInstant());
        },
        coordinate.longitude(),
        coordinate.latitude(),
        Timestamp.from(batch.observedAt()),
        policy.linkTtl().toMillis(),
        policy.stopFreshnessTtl().toMillis(),
        policy.stopFreshnessTtl().toMillis(),
        Timestamp.from(batch.observedAt()),
        coordinate.longitude(),
        coordinate.latitude(),
        policy.radiusMeters(),
        policy.maxCandidates());
  }

  private void upsert(UUID placeId, CandidateWrite candidate, PlaceStopLinkBatch batch) {
    jdbcTemplate.update(
        """
        insert into public.place_stop_links (
          place_id, stop_id, distance_meters, walk_minutes, link_method, confidence,
          enabled, source_provider, observed_at, expires_at, tombstoned_at
        ) values (?, ?, ?, ?, 'spatial_radius', 1.000, true, ?, ?, ?, null)
        on conflict (place_id, stop_id) do update
        set distance_meters=excluded.distance_meters,
            walk_minutes=excluded.walk_minutes,
            link_method=excluded.link_method,
            confidence=excluded.confidence,
            enabled=true,
            source_provider=excluded.source_provider,
            observed_at=excluded.observed_at,
            expires_at=excluded.expires_at,
            tombstoned_at=null
        """,
        placeId,
        candidate.stopId(),
        candidate.distanceMeters(),
        candidate.walkMinutes(),
        batch.sourceProvider(),
        Timestamp.from(batch.observedAt()),
        Timestamp.from(candidate.expiresAt()));
  }

  private int tombstoneMissing(
      UUID placeId, List<CandidateWrite> candidates, PlaceStopLinkBatch batch) {
    List<UUID> activeStopIds =
        candidates.stream().map(CandidateWrite::stopId).sorted(Comparator.naturalOrder()).toList();
    if (activeStopIds.isEmpty()) {
      return jdbcTemplate.update(
          """
          update public.place_stop_links
          set enabled=false, tombstoned_at=?, observed_at=?,
              expires_at=greatest(expires_at, ?::timestamptz + interval '1 microsecond')
          where place_id=? and source_provider=? and (enabled or tombstoned_at is null)
          """,
          Timestamp.from(batch.observedAt()),
          Timestamp.from(batch.observedAt()),
          Timestamp.from(batch.observedAt()),
          placeId,
          batch.sourceProvider());
    }
    return jdbcTemplate.update(
        """
        update public.place_stop_links
        set enabled=false, tombstoned_at=?, observed_at=?,
            expires_at=greatest(expires_at, ?::timestamptz + interval '1 microsecond')
        where place_id=? and source_provider=? and not (stop_id = any (?::uuid[]))
          and (enabled or tombstoned_at is null)
        """,
        Timestamp.from(batch.observedAt()),
        Timestamp.from(batch.observedAt()),
        Timestamp.from(batch.observedAt()),
        placeId,
        batch.sourceProvider(),
        activeStopIds.toArray(UUID[]::new));
  }

  private void advanceScope(UUID placeId, PlaceStopLinkBatch batch) {
    jdbcTemplate.update(
        """
        insert into public.place_stop_link_scope_states (
          place_id, source_provider, observed_at, manifest_fingerprint, updated_at
        ) values (?, ?, ?, ?, ?)
        on conflict (place_id, source_provider) do update
        set observed_at=excluded.observed_at,
            manifest_fingerprint=excluded.manifest_fingerprint,
            updated_at=excluded.updated_at
        where public.place_stop_link_scope_states.observed_at < excluded.observed_at
        """,
        placeId,
        batch.sourceProvider(),
        Timestamp.from(batch.observedAt()),
        batch.fingerprint(),
        Timestamp.from(batch.observedAt()));
  }

  private record PlaceCoordinate(double longitude, double latitude) {}

  private record CandidateWrite(
      UUID stopId, int distanceMeters, int walkMinutes, Instant expiresAt) {}

  private record ScopeState(Instant observedAt, String fingerprint) {}
}
