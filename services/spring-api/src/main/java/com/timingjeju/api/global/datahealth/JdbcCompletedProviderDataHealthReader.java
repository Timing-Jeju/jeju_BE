package com.timingjeju.api.global.datahealth;

import com.timingjeju.api.application.datahealth.CompletedProviderDataHealthCatalog;
import com.timingjeju.api.application.datahealth.ProviderDataHealthAttemptStatus;
import com.timingjeju.api.application.datahealth.ProviderDataHealthException;
import com.timingjeju.api.application.datahealth.ProviderDataHealthHistory;
import com.timingjeju.api.application.datahealth.ProviderDataHealthKey;
import com.timingjeju.api.application.datahealth.ProviderDataHealthReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCompletedProviderDataHealthReader implements ProviderDataHealthReader {
  private static final int MAX_KEYS = 8;
  private static final String NOT_REQUESTED = "__not_requested__";

  static final String SELECT_HEALTH =
      """
      with canonical(provider, service, operation) as (
        values
          (:provider0, :service0, :operation0),
          (:provider1, :service1, :operation1),
          (:provider2, :service2, :operation2),
          (:provider3, :service3, :operation3),
          (:provider4, :service4, :operation4),
          (:provider5, :service5, :operation5),
          (:provider6, :service6, :operation6),
          (:provider7, :service7, :operation7)
      )
      select canonical.provider,
             canonical.service,
             canonical.operation,
             health.last_attempt_at,
             health.latest_status,
             health.last_success_at,
             health.facts_as_of
      from canonical
      left join lateral (
        with recent as materialized (
          select import_run.id,
                 import_run.status,
                 import_run.finished_at,
                 import_run.source_provider,
                 import_run.source_service,
                 import_run.source_operation,
                 row_number() over (
                   order by import_run.started_at desc, import_run.id desc
                 ) as position
          from public.data_import_runs import_run
          where import_run.source_provider = canonical.provider
            and import_run.source_service = canonical.service
            and import_run.source_operation = canonical.operation
            and import_run.idempotency_key is not null
            and import_run.idempotency_enforced
            and import_run.running_scope_enforced
            and import_run.status in ('succeeded', 'failed', 'partial', 'cancelled')
            and import_run.finished_at is not null
          order by import_run.started_at desc, import_run.id desc
          limit 32
        ),
        enriched as materialized (
          select recent.status,
                 recent.finished_at,
                 recent.position,
                 facts.facts_as_of
          from recent
          left join lateral (
            select max(coalesce(snapshot.source_modified_at, snapshot.fetched_at)) as facts_as_of
            from public.external_api_snapshots snapshot
            where recent.status = 'succeeded'
              and snapshot.import_run_id = recent.id
              and snapshot.source_provider = recent.source_provider
              and snapshot.source_service = recent.source_service
              and snapshot.source_operation = recent.source_operation
              and snapshot.parse_status = 'parsed'
          ) facts on true
        )
        select max(enriched.finished_at) filter (where enriched.position = 1) as last_attempt_at,
               max(enriched.status) filter (where enriched.position = 1) as latest_status,
               max(enriched.finished_at) filter (
                 where enriched.position = (
                   select min(candidate.position)
                   from enriched candidate
                   where candidate.facts_as_of is not null
                 )
               ) as last_success_at,
               max(enriched.facts_as_of) filter (
                 where enriched.position = (
                   select min(candidate.position)
                   from enriched candidate
                   where candidate.facts_as_of is not null
                 )
               ) as facts_as_of
        from enriched
      ) health on true
      where canonical.provider <> :notRequested
        and health.last_attempt_at is not null
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcCompletedProviderDataHealthReader(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc는 필수입니다.");
  }

  @Override
  public List<ProviderDataHealthHistory> read(List<ProviderDataHealthKey> keys) {
    List<ProviderDataHealthKey> requested = validate(keys);
    if (requested.isEmpty()) {
      return List.of();
    }
    try {
      return jdbc
          .query(
              SELECT_HEALTH, parameters(requested), JdbcCompletedProviderDataHealthReader::mapRow)
          .stream()
          .sorted((left, right) -> left.key().compareTo(right.key()))
          .toList();
    } catch (DataAccessException failure) {
      throw ProviderDataHealthException.unavailable();
    }
  }

  static ProviderDataHealthHistory mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    try {
      ProviderDataHealthKey key =
          new ProviderDataHealthKey(
              required(resultSet.getString("provider")),
              required(resultSet.getString("service")),
              required(resultSet.getString("operation")));
      Timestamp attempt = resultSet.getTimestamp("last_attempt_at");
      if (attempt == null) {
        throw ProviderDataHealthException.unavailable();
      }
      String status = required(resultSet.getString("latest_status"));
      Timestamp success = resultSet.getTimestamp("last_success_at");
      Timestamp facts = resultSet.getTimestamp("facts_as_of");
      return new ProviderDataHealthHistory(
          key,
          attempt.toInstant(),
          ProviderDataHealthAttemptStatus.fromDatabase(status),
          success == null ? null : success.toInstant(),
          facts == null ? null : facts.toInstant());
    } catch (ProviderDataHealthException failure) {
      throw failure;
    } catch (IllegalArgumentException failure) {
      throw ProviderDataHealthException.unavailable();
    }
  }

  static MapSqlParameterSource parameters(List<ProviderDataHealthKey> keys) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource().addValue("notRequested", NOT_REQUESTED);
    for (int index = 0; index < MAX_KEYS; index++) {
      ProviderDataHealthKey key =
          index < keys.size()
              ? keys.get(index)
              : new ProviderDataHealthKey(NOT_REQUESTED, NOT_REQUESTED, NOT_REQUESTED);
      parameters
          .addValue("provider" + index, key.provider())
          .addValue("service" + index, key.service())
          .addValue("operation" + index, key.operation());
    }
    return parameters;
  }

  private static List<ProviderDataHealthKey> validate(List<ProviderDataHealthKey> values) {
    Objects.requireNonNull(values, "keys는 필수입니다.");
    if (values.size() > MAX_KEYS) {
      throw new IllegalArgumentException("조회 key는 최대 8개입니다.");
    }
    Set<ProviderDataHealthKey> canonical = Set.copyOf(CompletedProviderDataHealthCatalog.keys());
    Set<ProviderDataHealthKey> unique = new HashSet<>();
    return values.stream()
        .map(key -> Objects.requireNonNull(key, "key는 필수입니다."))
        .peek(
            key -> {
              if (!canonical.contains(key)) {
                throw new IllegalArgumentException("canonical 완료 공급자 key만 조회할 수 있습니다.");
              }
              if (!unique.add(key)) {
                throw new IllegalArgumentException("조회 key가 중복되었습니다.");
              }
            })
        .sorted()
        .toList();
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) {
      throw ProviderDataHealthException.unavailable();
    }
    return value;
  }
}
