package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.tago.arrival.TagoArrival;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCacheKey;
import com.timingjeju.api.application.tago.arrival.TagoArrivalCommitCommand;
import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalRepository;
import com.timingjeju.api.application.tago.arrival.TagoArrivalSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTagoArrivalRepository implements TagoArrivalRepository {
  private static final String STOP_REFERENCE_SERVICE = "BusSttnInfoInqireService";
  private final JdbcTemplate jdbcTemplate;

  public JdbcTagoArrivalRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int append(TagoArrivalCommitCommand command) {
    int inserted = 0;
    for (TagoArrival arrival : command.arrivals()) {
      int updated =
          jdbcTemplate.update(
              """
              insert into public.bus_arrival_snapshots (
                stop_id, route_id, external_route_id, route_no, route_type, direction_name,
                estimated_arrival_seconds, remaining_stops, vehicle_type,
                observed_at, expires_at, source_provider, source_service, source_operation,
                import_run_id, source_snapshot_id, raw_payload
              )
              select stop.id, route.id, ?, ?, ?, route.direction_name, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb
              from public.bus_stops stop
              left join lateral (
                select candidate.id, candidate.direction_name
                from public.bus_routes candidate
                where candidate.source_provider=? and candidate.city_code=?
                  and candidate.external_route_id=?
                order by candidate.updated_at desc, candidate.id
                limit 1
              ) route on true
              where stop.id=? and stop.source_provider=? and stop.source_service=?
                and stop.city_code=? and stop.node_id=? and not stop.stale
              """,
              arrival.externalRouteId(),
              arrival.routeNo(),
              arrival.routeType(),
              arrival.estimatedArrivalSeconds(),
              arrival.remainingStops(),
              arrival.vehicleType(),
              Timestamp.from(command.observedAt()),
              Timestamp.from(command.expiresAt()),
              command.key().provider(),
              command.key().service(),
              TagoArrivalImportSessionAdapter.OPERATION,
              command.lease().runId(),
              command.snapshot().snapshotId(),
              command.key().provider(),
              command.key().cityCode(),
              arrival.externalRouteId(),
              command.key().stopId(),
              command.key().provider(),
              STOP_REFERENCE_SERVICE,
              command.key().cityCode(),
              command.key().nodeId());
      if (updated != 1) throw TagoArrivalException.invalidRequest();
      inserted += updated;
    }
    return inserted;
  }

  @Override
  public Optional<TagoArrivalSnapshot> findLatest(TagoArrivalCacheKey key) {
    List<Lineage> latest =
        jdbcTemplate.query(
            """
            select observed_at, expires_at, import_run_id, source_snapshot_id
            from public.bus_arrival_snapshots
            where source_provider=? and source_service=? and stop_id=?
              and octet_length(source_provider) <= 128
              and octet_length(source_service) <= 128
              and remaining_stops is not null
              and estimated_arrival_seconds between 0 and 86400
              and remaining_stops between 0 and 10000
            order by observed_at desc, source_snapshot_id desc
            limit 1
            """,
            (resultSet, rowNumber) ->
                new Lineage(
                    resultSet.getTimestamp("observed_at").toInstant(),
                    resultSet.getTimestamp("expires_at").toInstant(),
                    resultSet.getObject("import_run_id", UUID.class),
                    resultSet.getObject("source_snapshot_id", UUID.class)),
            key.provider(),
            key.service(),
            key.stopId());
    if (latest.isEmpty()) return Optional.empty();
    Lineage lineage = latest.getFirst();
    List<TagoArrival> arrivals =
        jdbcTemplate.query(
            """
            select arrival.external_route_id, arrival.route_no, arrival.route_type,
                   arrival.vehicle_type, arrival.estimated_arrival_seconds,
                   arrival.remaining_stops
            from public.bus_arrival_snapshots arrival
            where arrival.source_provider=? and arrival.source_service=? and arrival.stop_id=?
              and arrival.observed_at=? and arrival.expires_at=?
              and arrival.import_run_id=? and arrival.source_snapshot_id=?
            order by arrival.estimated_arrival_seconds, arrival.route_no, arrival.id
            """,
            (resultSet, rowNumber) ->
                new TagoArrival(
                    resultSet.getString("external_route_id"),
                    resultSet.getString("route_no"),
                    resultSet.getString("route_type"),
                    resultSet.getString("vehicle_type"),
                    resultSet.getInt("estimated_arrival_seconds"),
                    resultSet.getInt("remaining_stops")),
            key.provider(),
            key.service(),
            key.stopId(),
            Timestamp.from(lineage.observedAt()),
            Timestamp.from(lineage.expiresAt()),
            lineage.importRunId(),
            lineage.sourceSnapshotId());
    if (arrivals.isEmpty()) return Optional.empty();
    return Optional.of(
        new TagoArrivalSnapshot(
            arrivals,
            lineage.observedAt(),
            lineage.expiresAt(),
            false,
            lineage.importRunId(),
            lineage.sourceSnapshotId()));
  }

  private record Lineage(
      Instant observedAt, Instant expiresAt, UUID importRunId, UUID sourceSnapshotId) {}
}
