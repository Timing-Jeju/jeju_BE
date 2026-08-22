package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTagoArrivalFlightStore implements TagoArrivalFlightStore {
  private static final String INSERT_CLAIM_SQL =
      """
      insert into public.tago_arrival_flights (
        fingerprint, generation, owner_token, lease_expires_at, state, outcome_code,
        retain_until, updated_at
      ) values (?, 1, ?, statement_timestamp() + (? * interval '1 millisecond'),
                'running', null,
                statement_timestamp() + (? * interval '1 millisecond'), statement_timestamp())
      on conflict (fingerprint) do nothing
      returning state, outcome_code, owner_token, generation
      """;

  private static final String RECLAIM_SQL =
      """
      with candidate as (
        select fingerprint, state
        from public.tago_arrival_flights
        where fingerprint=?
          and ((state='running' and lease_expires_at <= statement_timestamp())
            or (state <> 'running' and retain_until <= statement_timestamp()))
        for update skip locked
      )
      update public.tago_arrival_flights target set
        generation = case when candidate.state <> 'running'
          then target.generation + 1 else target.generation end,
        owner_token = case when candidate.state <> 'running'
          then ? else target.owner_token end,
        lease_expires_at = case when candidate.state <> 'running'
          then statement_timestamp() + (? * interval '1 millisecond')
          else target.lease_expires_at end,
        state = case
          when candidate.state = 'running' then 'abandoned' else 'running' end,
        outcome_code = case
          when candidate.state = 'running' then 'data_unavailable' else null end,
        retain_until = case
          when candidate.state = 'running'
          then statement_timestamp() + (? * interval '1 millisecond')
          else statement_timestamp() + (? * interval '1 millisecond') end,
        updated_at = statement_timestamp()
      from candidate
      where target.fingerprint=candidate.fingerprint
      returning target.state, target.outcome_code, target.owner_token, target.generation
      """;

  private static final String OBSERVE_SQL =
      """
      select state, outcome_code, owner_token, generation
      from public.tago_arrival_flights
      where fingerprint=?
        and (state='running'
          or (state <> 'running' and retain_until > clock_timestamp()))
      """;

  private final JdbcTemplate jdbc;

  public JdbcTagoArrivalFlightStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc는 필수입니다.");
  }

  @Override
  public TagoArrivalFlightDecision observeOrClaim(
      String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
    try {
      cleanupExpiredTerminals(fingerprint, 32);
      List<TagoArrivalFlightDecision> inserted =
          jdbc.query(
              INSERT_CLAIM_SQL,
              (resultSet, rowNumber) -> mapDecision(resultSet, fingerprint, proposedOwner),
              fingerprint,
              proposedOwner,
              lease.toMillis(),
              lease.toMillis());
      if (!inserted.isEmpty()) return exactlyOne(inserted);

      List<TagoArrivalFlightDecision> reclaimed =
          jdbc.query(
              RECLAIM_SQL,
              (resultSet, rowNumber) -> mapDecision(resultSet, fingerprint, proposedOwner),
              fingerprint,
              proposedOwner,
              lease.toMillis(),
              quarantine.toMillis(),
              lease.toMillis());
      if (!reclaimed.isEmpty()) return exactlyOne(reclaimed);

      List<TagoArrivalFlightDecision> observed =
          jdbc.query(
              OBSERVE_SQL,
              (resultSet, rowNumber) -> mapDecision(resultSet, fingerprint, proposedOwner),
              fingerprint);
      if (observed.isEmpty()) return TagoArrivalFlightDecision.contended();
      return exactlyOne(observed);
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static TagoArrivalFlightDecision exactlyOne(List<TagoArrivalFlightDecision> rows) {
    if (rows.size() != 1) throw TagoArrivalException.dataUnavailable();
    return rows.getFirst();
  }

  @Override
  public boolean completeSuccess(TagoArrivalFlightLease lease, Duration retain) {
    return terminal(lease, "succeeded", null, null, retain);
  }

  @Override
  public boolean completeSuccess(
      TagoArrivalFlightLease lease, Instant sourceExpiresAt, Duration retain) {
    return terminal(lease, "succeeded", null, sourceExpiresAt, retain);
  }

  @Override
  public boolean completeFailure(
      TagoArrivalFlightLease lease, TagoArrivalException.Code code, Duration retain) {
    return terminal(lease, "failed", databaseCode(code), null, retain);
  }

  @Override
  public void lockCurrent(TagoArrivalFlightLease lease) {
    try {
      List<Integer> rows =
          jdbc.query(
              """
              select 1
              from public.tago_arrival_flights
              where fingerprint=? and generation=? and owner_token=? and state='running'
                and lease_expires_at > clock_timestamp()
              for update
              """,
              (resultSet, rowNumber) -> 1,
              lease.fingerprint(),
              lease.generation(),
              lease.ownerToken());
      if (rows.size() != 1) throw TagoArrivalException.dataUnavailable();
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  @Override
  public int cleanupExpiredTerminals(String currentFingerprint, int limit) {
    if (limit < 1 || limit > 32) throw new IllegalArgumentException("cleanup limit은 1~32입니다.");
    try {
      return jdbc.update(
          """
          with expired as (
            select fingerprint
            from public.tago_arrival_flights
            where state <> 'running' and retain_until <= clock_timestamp()
              and fingerprint <> ?
            order by retain_until, fingerprint
            for update skip locked
            limit ?
          )
          delete from public.tago_arrival_flights target
          using expired
          where target.fingerprint=expired.fingerprint
          """,
          currentFingerprint,
          limit);
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  @Override
  public boolean abandon(TagoArrivalFlightLease lease, Duration quarantine) {
    try {
      return jdbc.update(
              """
              update public.tago_arrival_flights
              set state='abandoned', outcome_code='data_unavailable',
                  retain_until=statement_timestamp() + (? * interval '1 millisecond'),
                  updated_at=statement_timestamp()
              where fingerprint=? and generation=? and owner_token=? and state='running'
              """,
              quarantine.toMillis(),
              lease.fingerprint(),
              lease.generation(),
              lease.ownerToken())
          == 1;
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private boolean terminal(
      TagoArrivalFlightLease lease,
      String state,
      String outcome,
      Instant sourceExpiresAt,
      Duration retain) {
    try {
      int updated =
          jdbc.update(
              """
              update public.tago_arrival_flights
              set state=?, outcome_code=?,
                  retain_until=case when ?::timestamptz is null
                    then clock_timestamp() + (? * interval '1 millisecond')
                    else least(?::timestamptz,
                      clock_timestamp() + (? * interval '1 millisecond')) end,
                  updated_at=clock_timestamp()
              where fingerprint=? and generation=? and owner_token=? and state='running'
                and lease_expires_at > clock_timestamp()
                and (?::timestamptz is null or ?::timestamptz > clock_timestamp())
              """,
              state,
              outcome,
              sourceExpiresAt == null ? null : Timestamp.from(sourceExpiresAt),
              retain.toMillis(),
              sourceExpiresAt == null ? null : Timestamp.from(sourceExpiresAt),
              retain.toMillis(),
              lease.fingerprint(),
              lease.generation(),
              lease.ownerToken(),
              sourceExpiresAt == null ? null : Timestamp.from(sourceExpiresAt),
              sourceExpiresAt == null ? null : Timestamp.from(sourceExpiresAt));
      if (updated == 1) return true;
      Integer exact =
          jdbc.queryForObject(
              """
              select count(*)
              from public.tago_arrival_flights
              where fingerprint=? and generation=? and owner_token=? and state=?
                and outcome_code is not distinct from ? and retain_until > clock_timestamp()
              """,
              Integer.class,
              lease.fingerprint(),
              lease.generation(),
              lease.ownerToken(),
              state,
              outcome);
      return exact != null && exact == 1;
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  static TagoArrivalFlightDecision mapDecision(
      ResultSet resultSet, String fingerprint, UUID proposedOwner) throws SQLException {
    String state = resultSet.getString("state");
    String outcome = resultSet.getString("outcome_code");
    UUID owner = resultSet.getObject("owner_token", UUID.class);
    long generation = resultSet.getLong("generation");
    try {
      TagoArrivalFlightLease lease = new TagoArrivalFlightLease(fingerprint, generation, owner);
      return switch (state) {
        case "running" ->
            owner.equals(proposedOwner)
                ? TagoArrivalFlightDecision.leader(fingerprint, generation, owner)
                : TagoArrivalFlightDecision.running(lease);
        case "succeeded" -> TagoArrivalFlightDecision.succeeded(lease);
        case "failed" -> TagoArrivalFlightDecision.failed(lease, applicationCode(outcome));
        case "abandoned" -> TagoArrivalFlightDecision.abandoned(lease);
        default -> throw TagoArrivalException.dataUnavailable();
      };
    } catch (IllegalArgumentException | NullPointerException mappingFailure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  private static String databaseCode(TagoArrivalException.Code code) {
    return code.name().toLowerCase(Locale.ROOT);
  }

  private static TagoArrivalException.Code applicationCode(String code) {
    if (code == null) throw TagoArrivalException.dataUnavailable();
    try {
      return TagoArrivalException.Code.valueOf(code.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }
}
