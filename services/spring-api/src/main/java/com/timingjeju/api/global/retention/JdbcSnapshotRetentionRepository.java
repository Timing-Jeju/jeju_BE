package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SnapshotRetentionCommand;
import com.timingjeju.api.application.retention.SnapshotRetentionException;
import com.timingjeju.api.application.retention.SnapshotRetentionOutcome;
import com.timingjeju.api.application.retention.SnapshotRetentionPort;
import com.timingjeju.api.application.retention.SnapshotRetentionResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSnapshotRetentionRepository implements SnapshotRetentionPort {
  static final String CANDIDATE_SQL =
      """
      with candidates as materialized (
        select snapshot.id
        from public.external_api_snapshots snapshot
        join public.data_import_runs import_run on import_run.id = snapshot.import_run_id
        where snapshot.purge_after <= :now
          and snapshot.raw_payload is not null
          and snapshot.purged_at is null
          and snapshot.source_provider in (:providers)
          and import_run.status <> 'running'
        order by snapshot.purge_after, snapshot.id
        limit :batchSize
        for update of snapshot, import_run skip locked
      )
      """;

  static final String DRY_RUN_SQL = CANDIDATE_SQL + "select count(*) from candidates";

  static final String MUTATION_SQL =
      CANDIDATE_SQL
          + """
          , updated as (
            update public.external_api_snapshots snapshot
            set raw_payload = null,
                purged_at = :now
            from candidates
            where snapshot.id = candidates.id
            returning 1
          )
          select count(*) from updated
          """;

  private final NamedParameterJdbcTemplate jdbc;
  private final LongSupplier nanoTime;

  @Autowired
  public JdbcSnapshotRetentionRepository(NamedParameterJdbcTemplate jdbc) {
    this(jdbc, System::nanoTime);
  }

  JdbcSnapshotRetentionRepository(NamedParameterJdbcTemplate jdbc, LongSupplier nanoTime) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc는 필수입니다.");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime은 필수입니다.");
  }

  @Override
  @Transactional
  public SnapshotRetentionResult execute(SnapshotRetentionCommand command) {
    Objects.requireNonNull(command, "command는 필수입니다.");
    long started = nanoTime.getAsLong();
    try {
      MapSqlParameterSource parameters =
          new MapSqlParameterSource()
              .addValue("now", Timestamp.from(command.now()))
              .addValue("providers", command.providers())
              .addValue("batchSize", command.batchSize());
      Integer count =
          jdbc.queryForObject(
              command.dryRun() ? DRY_RUN_SQL : MUTATION_SQL, parameters, Integer.class);
      if (count == null || count < 0) {
        throw SnapshotRetentionException.unavailable();
      }
      return new SnapshotRetentionResult(
          count,
          command.dryRun() ? 0 : count,
          Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - started)),
          SnapshotRetentionOutcome.SUCCESS,
          command.dryRun());
    } catch (DataAccessException failure) {
      throw SnapshotRetentionException.unavailable();
    }
  }
}
