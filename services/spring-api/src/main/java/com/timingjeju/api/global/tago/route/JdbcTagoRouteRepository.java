package com.timingjeju.api.global.tago.route;

import com.timingjeju.api.application.tago.route.TagoRoute;
import com.timingjeju.api.application.tago.route.TagoRouteImportException;
import com.timingjeju.api.application.tago.route.TagoRouteRepository;
import com.timingjeju.api.application.tago.route.TagoRouteStopWrite;
import com.timingjeju.api.application.tago.route.TagoRouteWrite;
import com.timingjeju.api.application.tago.route.TagoRouteWriteResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTagoRouteRepository implements TagoRouteRepository {
  static final String PROVIDER = "TAGO";
  static final String SERVICE = "BusRouteInfoInqireService";
  private final JdbcTemplate jdbc;

  public JdbcTagoRouteRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public TagoRouteWriteResult apply(
      List<TagoRouteWrite> routes,
      List<TagoRouteStopWrite> routeStops,
      UUID runId,
      Instant observedAt) {
    try {
      Map<String, UUID> routeIds = new HashMap<>();
      java.util.Set<String> staleWrites = new java.util.HashSet<>();
      int inserted = 0;
      int updated = 0;
      int skipped = 0;
      int deleted = 0;
      for (TagoRouteWrite write : routes) {
        if (!runId.equals(write.importRunId()) || !"39".equals(write.route().cityCode()))
          throw TagoRouteImportException.invalidResponse();
        lockNaturalKey(write.route());
        StoredRoute existing = findRoute(write.route());
        if (existing != null && write.observedAt().isBefore(existing.lastSeenAt())) {
          routeIds.put(write.route().externalRouteId(), existing.id());
          staleWrites.add(write.route().externalRouteId());
          skipped++;
          continue;
        }
        UUID id = upsertRoute(write);
        routeIds.put(write.route().externalRouteId(), id);
        if (existing == null) inserted++;
        else updated++;
      }
      Map<String, List<TagoRouteStopWrite>> byRoute =
          routeStops.stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(write -> write.stop().externalRouteId()));
      for (TagoRouteWrite routeWrite : routes) {
        UUID routeId = routeIds.get(routeWrite.route().externalRouteId());
        if (staleWrites.contains(routeWrite.route().externalRouteId())) continue;
        List<TagoRouteStopWrite> stops =
            byRoute.getOrDefault(routeWrite.route().externalRouteId(), List.of());
        List<ExistingStop> existingStops =
            existingStops(routeId, routeWrite.route().directionKey());
        boolean sameSequence = sameSequence(existingStops, stops);
        if (!sameSequence) {
          deleted +=
              jdbc.update(
                  "delete from public.route_stops where route_id = ? and direction_key = ?",
                  routeId,
                  routeWrite.route().directionKey());
        }
        for (TagoRouteStopWrite stop : stops) {
          if (!runId.equals(stop.importRunId())
              || !routeWrite.route().directionKey().equals(stop.directionKey()))
            throw TagoRouteImportException.invalidResponse();
          int count =
              sameSequence
                  ? refreshRouteStop(routeId, stop)
                  : insertRouteStop(routeId, stop, runId);
          if (count != 1) throw TagoRouteImportException.stopScopeMismatch();
        }
      }
      return new TagoRouteWriteResult(inserted, updated, skipped, deleted);
    } catch (TagoRouteImportException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw TagoRouteImportException.invalidResponse(failure);
    }
  }

  private StoredRoute findRoute(TagoRoute route) {
    List<StoredRoute> rows =
        jdbc.query(
            "select id, last_seen_at from public.bus_routes where source_provider = ? and source_service = ? and city_code = ? and external_route_id = ? for update",
            (rs, row) ->
                new StoredRoute(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()),
            PROVIDER,
            SERVICE,
            route.cityCode(),
            route.externalRouteId());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void lockNaturalKey(TagoRoute route) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        lengthPrefixed(PROVIDER)
            + lengthPrefixed(SERVICE)
            + lengthPrefixed(route.cityCode())
            + lengthPrefixed(route.externalRouteId()));
  }

  private static String lengthPrefixed(String value) {
    return value.length() + ":" + value;
  }

  private List<ExistingStop> existingStops(UUID routeId, String directionKey) {
    return jdbc.query(
        "select stop.node_id, route_stop.stop_sequence from public.route_stops route_stop join public.bus_stops stop on stop.id = route_stop.stop_id where route_stop.route_id = ? and route_stop.direction_key = ? order by route_stop.stop_sequence for update of route_stop",
        (rs, row) -> new ExistingStop(rs.getString(1), rs.getInt(2)),
        routeId,
        directionKey);
  }

  private static boolean sameSequence(
      List<ExistingStop> existing, List<TagoRouteStopWrite> incoming) {
    if (existing.size() != incoming.size()) return false;
    for (int index = 0; index < existing.size(); index++) {
      if (!existing.get(index).nodeId().equals(incoming.get(index).stop().nodeId())
          || existing.get(index).sequence() != incoming.get(index).stop().stopSequence())
        return false;
    }
    return true;
  }

  private int refreshRouteStop(UUID routeId, TagoRouteStopWrite stop) {
    return jdbc.update(
        "update public.route_stops route_stop set import_run_id = ?, source_snapshot_id = ?, last_seen_at = ?, stale_at = null, tombstoned_at = null where route_stop.route_id = ? and route_stop.direction_key = ? and route_stop.stop_sequence = ? and route_stop.stop_id = (select id from public.bus_stops where source_provider = 'TAGO' and source_service = 'BusSttnInfoInqireService' and city_code = ? and node_id = ? and stale = false and tombstoned_at is null)",
        stop.importRunId(),
        stop.snapshotId(),
        Timestamp.from(stop.observedAt()),
        routeId,
        stop.directionKey(),
        stop.stop().stopSequence(),
        stop.stop().cityCode(),
        stop.stop().nodeId());
  }

  private int insertRouteStop(UUID routeId, TagoRouteStopWrite stop, UUID runId) {
    return jdbc.update(
        "insert into public.route_stops (route_id, stop_id, direction_key, stop_sequence, import_run_id, source_snapshot_id, last_seen_at, stale_at, tombstoned_at, source_provider, city_code) select ?, id, ?, ?, ?, ?, ?, null, null, ?, ? from public.bus_stops where source_provider = 'TAGO' and source_service = 'BusSttnInfoInqireService' and city_code = ? and node_id = ? and stale = false and tombstoned_at is null",
        routeId,
        stop.directionKey(),
        stop.stop().stopSequence(),
        runId,
        stop.snapshotId(),
        Timestamp.from(stop.observedAt()),
        PROVIDER,
        stop.stop().cityCode(),
        stop.stop().cityCode(),
        stop.stop().nodeId());
  }

  private UUID upsertRoute(TagoRouteWrite write) {
    return jdbc.queryForObject(
        "insert into public.bus_routes (external_route_id, route_no, route_type, direction_name, source_provider, source_service, city_code, import_run_id, source_snapshot_id, last_seen_at, stale, stale_at, tombstoned_at, source_deleted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, null, null, null) on conflict (source_provider, source_service, city_code, external_route_id) where external_route_id is not null and octet_length(source_provider) <= 128 and octet_length(source_service) <= 128 and octet_length(city_code) <= 64 and octet_length(external_route_id) <= 512 and octet_length(source_provider) + octet_length(source_service) + octet_length(city_code) + octet_length(external_route_id) <= 1024 do update set route_no = excluded.route_no, route_type = excluded.route_type, direction_name = excluded.direction_name, import_run_id = excluded.import_run_id, source_snapshot_id = excluded.source_snapshot_id, last_seen_at = excluded.last_seen_at, stale = false, stale_at = null, tombstoned_at = null, source_deleted_at = null where excluded.last_seen_at >= bus_routes.last_seen_at returning id",
        UUID.class,
        write.route().externalRouteId(),
        write.route().routeNo(),
        write.route().routeType(),
        write.route().startNodeName() + " → " + write.route().endNodeName(),
        PROVIDER,
        SERVICE,
        write.route().cityCode(),
        write.importRunId(),
        write.snapshotId(),
        Timestamp.from(write.observedAt()));
  }

  private record StoredRoute(UUID id, Instant lastSeenAt) {}

  private record ExistingStop(String nodeId, int sequence) {}
}
