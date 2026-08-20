package com.timingjeju.api.global.tago.stop;

import com.timingjeju.api.application.tago.stop.TagoCityCode;
import com.timingjeju.api.application.tago.stop.TagoStation;
import com.timingjeju.api.application.tago.stop.TagoStopImportException;
import com.timingjeju.api.application.tago.stop.TagoStopPageLineage;
import com.timingjeju.api.application.tago.stop.TagoStopRepository;
import com.timingjeju.api.application.tago.stop.TagoStopWrite;
import com.timingjeju.api.application.tago.stop.TagoStopWriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTagoStopRepository implements TagoStopRepository {
  private static final String PROVIDER = "TAGO";
  private static final String SERVICE = "BusSttnInfoInqireService";
  private final JdbcTemplate jdbcTemplate;

  public JdbcTagoStopRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate은 필수입니다.");
  }

  @Override
  @Transactional
  public TagoStopWriteResult apply(
      TagoCityCode city,
      TagoStopPageLineage cityLineage,
      TagoStopPageLineage stationSweepLineage,
      List<TagoStopWrite> stations,
      UUID runId,
      Instant observedAt) {
    Objects.requireNonNull(city, "city는 필수입니다.");
    Objects.requireNonNull(cityLineage, "cityLineage는 필수입니다.");
    Objects.requireNonNull(stationSweepLineage, "stationSweepLineage는 필수입니다.");
    Objects.requireNonNull(stations, "stations는 필수입니다.");
    try {
      if (stations.stream()
          .anyMatch(
              write ->
                  write == null
                      || !runId.equals(write.importRunId())
                      || !city.code().equals(write.station().cityCode()))) {
        throw TagoStopImportException.invalidResponse();
      }
      upsertCity(city, cityLineage, runId, observedAt);
      Map<String, StoredStop> existing = lockScope(city.code());
      Set<String> seen = new HashSet<>();
      Counts counts = new Counts();
      for (TagoStopWrite write : stations) {
        if (!seen.add(write.station().nodeId())) throw TagoStopImportException.invalidResponse();
        StoredStop stored = existing.get(write.station().nodeId());
        if (stored == null) {
          if (insert(write) == 1) {
            counts.inserted++;
          } else {
            StoredStop concurrent = findOne(write.station());
            applyExisting(concurrent, write, counts);
          }
        } else if (write.observedAt().isBefore(stored.lastSeenAt())) {
          counts.skipped++;
        } else if (stored.same(write.station())) {
          refresh(stored.id(), write);
          counts.skipped++;
        } else {
          update(stored.id(), write);
          counts.updated++;
        }
      }
      for (StoredStop stored : existing.values()) {
        if (!seen.contains(stored.nodeId()) && !stored.stale()) {
          stale(stored.id(), stationSweepLineage.snapshotId(), runId, observedAt);
          counts.staled++;
        }
      }
      return new TagoStopWriteResult(
          counts.inserted, counts.updated, counts.skipped, counts.staled);
    } catch (TagoStopImportException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw TagoStopImportException.invalidResponse(failure);
    }
  }

  private void applyExisting(StoredStop stored, TagoStopWrite write, Counts counts) {
    if (stored == null) throw TagoStopImportException.invalidResponse();
    if (write.observedAt().isBefore(stored.lastSeenAt())) {
      counts.skipped++;
    } else if (stored.same(write.station())) {
      refresh(stored.id(), write);
      counts.skipped++;
    } else {
      update(stored.id(), write);
      counts.updated++;
    }
  }

  private void upsertCity(
      TagoCityCode city, TagoStopPageLineage lineage, UUID runId, Instant observedAt) {
    jdbcTemplate.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> null,
        PROVIDER + '|' + SERVICE + "|city|" + city.code());
    List<StoredCity> rows =
        jdbcTemplate.query(
            """
            select code_name, source_snapshot_id, import_run_id, last_seen_at
            from public.external_reference_codes
            where source_provider=? and source_service=? and code_type='city'
              and external_code=? and valid_from='-infinity'::date
            for update
            """,
            (resultSet, rowNumber) ->
                new StoredCity(
                    resultSet.getString("code_name"),
                    resultSet.getObject("source_snapshot_id", UUID.class),
                    resultSet.getObject("import_run_id", UUID.class),
                    resultSet.getTimestamp("last_seen_at").toInstant()),
            PROVIDER,
            SERVICE,
            city.code());
    if (!rows.isEmpty()) {
      StoredCity stored = rows.getFirst();
      if (stored.snapshotId().equals(lineage.snapshotId()) && stored.runId().equals(runId)) {
        if (!stored.name().equals(city.name())) throw TagoStopImportException.invalidResponse();
        return;
      }
      if (observedAt.isBefore(stored.lastSeenAt())) return;
    }
    jdbcTemplate.update(
        """
        insert into public.external_reference_codes
          (source_provider, source_service, code_type, external_code, code_name, attributes,
           valid_from, source_snapshot_id, import_run_id, last_seen_at)
        values (?, ?, 'city', ?, ?, '{"scope":"jeju"}'::jsonb, '-infinity'::date, ?, ?, ?)
        on conflict (source_provider, source_service, code_type, external_code, valid_from)
        do update set code_name=excluded.code_name, attributes=excluded.attributes,
          source_snapshot_id=excluded.source_snapshot_id, import_run_id=excluded.import_run_id,
          last_seen_at=greatest(external_reference_codes.last_seen_at, excluded.last_seen_at),
          stale_at=null, tombstoned_at=null, updated_at=now()
        """,
        PROVIDER,
        SERVICE,
        city.code(),
        city.name(),
        lineage.snapshotId(),
        runId,
        timestamp(observedAt));
  }

  private Map<String, StoredStop> lockScope(String cityCode) {
    List<StoredStop> rows =
        jdbcTemplate.query(
            """
            select id, node_id, node_name, node_no,
                   ST_X(location::geometry) longitude, ST_Y(location::geometry) latitude,
                   last_seen_at, stale
            from public.bus_stops
            where source_provider=? and source_service=? and city_code=?
            order by node_id for update
            """,
            (resultSet, rowNumber) -> stored(resultSet),
            PROVIDER,
            SERVICE,
            cityCode);
    Map<String, StoredStop> result = new HashMap<>();
    rows.forEach(row -> result.put(row.nodeId(), row));
    return result;
  }

  private int insert(TagoStopWrite write) {
    TagoStation station = write.station();
    return jdbcTemplate.update(
        """
        insert into public.bus_stops
          (external_stop_id, node_id, node_name, node_no, location, source_provider,
           source_service, city_code, import_run_id, source_snapshot_id, last_seen_at, stale)
        values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ?, ?, ?, ?, ?, ?, false) on conflict do nothing
        """,
        station.nodeId(),
        station.nodeId(),
        station.nodeName(),
        station.nodeNo(),
        station.longitude(),
        station.latitude(),
        PROVIDER,
        SERVICE,
        station.cityCode(),
        write.importRunId(),
        write.snapshotId(),
        timestamp(write.observedAt()));
  }

  private StoredStop findOne(TagoStation station) {
    List<StoredStop> rows =
        jdbcTemplate.query(
            """
            select id, node_id, node_name, node_no,
                   ST_X(location::geometry) longitude, ST_Y(location::geometry) latitude,
                   last_seen_at, stale
            from public.bus_stops
            where source_provider=? and source_service=? and city_code=? and node_id=?
            for update
            """,
            (resultSet, rowNumber) -> stored(resultSet),
            PROVIDER,
            SERVICE,
            station.cityCode(),
            station.nodeId());
    return rows.stream().findFirst().orElse(null);
  }

  private void update(UUID id, TagoStopWrite write) {
    TagoStation station = write.station();
    jdbcTemplate.update(
        """
        update public.bus_stops set node_name=?, node_no=?,
          location=ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
          import_run_id=?, source_snapshot_id=?, last_seen_at=?, stale=false,
          stale_at=null, tombstoned_at=null, source_deleted_at=null, updated_at=now()
        where id=?
        """,
        station.nodeName(),
        station.nodeNo(),
        station.longitude(),
        station.latitude(),
        write.importRunId(),
        write.snapshotId(),
        timestamp(write.observedAt()),
        id);
  }

  private void refresh(UUID id, TagoStopWrite write) {
    jdbcTemplate.update(
        """
        update public.bus_stops set import_run_id=?, source_snapshot_id=?, last_seen_at=?,
          stale=false, stale_at=null, tombstoned_at=null, source_deleted_at=null, updated_at=now()
        where id=?
        """,
        write.importRunId(),
        write.snapshotId(),
        timestamp(write.observedAt()),
        id);
  }

  private void stale(UUID id, UUID snapshotId, UUID runId, Instant observedAt) {
    jdbcTemplate.update(
        """
        update public.bus_stops set stale=true, stale_at=coalesce(stale_at, ?),
          import_run_id=?, source_snapshot_id=?, updated_at=now() where id=?
        """,
        timestamp(observedAt),
        runId,
        snapshotId,
        id);
  }

  private static StoredStop stored(ResultSet resultSet) throws SQLException {
    return new StoredStop(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("node_id"),
        resultSet.getString("node_name"),
        resultSet.getString("node_no"),
        resultSet.getDouble("longitude"),
        resultSet.getDouble("latitude"),
        resultSet.getTimestamp("last_seen_at").toInstant(),
        resultSet.getBoolean("stale"));
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.from(value);
  }

  private record StoredStop(
      UUID id,
      String nodeId,
      String nodeName,
      String nodeNo,
      double longitude,
      double latitude,
      Instant lastSeenAt,
      boolean stale) {
    private boolean same(TagoStation station) {
      return Objects.equals(nodeName, station.nodeName())
          && Objects.equals(nodeNo, station.nodeNo())
          && Double.compare(longitude, station.longitude()) == 0
          && Double.compare(latitude, station.latitude()) == 0
          && !stale;
    }
  }

  private record StoredCity(String name, UUID snapshotId, UUID runId, Instant lastSeenAt) {}

  private static final class Counts {
    private int inserted;
    private int updated;
    private int skipped;
    private int staled;
  }
}
