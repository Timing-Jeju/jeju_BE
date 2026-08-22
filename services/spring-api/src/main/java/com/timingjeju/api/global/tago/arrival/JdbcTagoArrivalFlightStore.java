package com.timingjeju.api.global.tago.arrival;

import com.timingjeju.api.application.tago.arrival.TagoArrivalException;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightDecision;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightLease;
import com.timingjeju.api.application.tago.arrival.TagoArrivalFlightStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTagoArrivalFlightStore implements TagoArrivalFlightStore {
  private static final String OBSERVE_OR_CLAIM_SQL =
      """
      insert into public.tago_arrival_flights (
        fingerprint, generation, owner_token, lease_expires_at, state, outcome_code,
        retain_until, updated_at
      ) values (?, 1, ?, statement_timestamp() + (? * interval '1 millisecond'),
                'running', null,
                statement_timestamp() + (? * interval '1 millisecond'), statement_timestamp())
      on conflict (fingerprint) do update set
        generation = case
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp()
          then tago_arrival_flights.generation + 1 else tago_arrival_flights.generation end,
        owner_token = case
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp()
          then excluded.owner_token else tago_arrival_flights.owner_token end,
        lease_expires_at = case
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp()
          then excluded.lease_expires_at else tago_arrival_flights.lease_expires_at end,
        state = case
          when tago_arrival_flights.state = 'running'
            and tago_arrival_flights.lease_expires_at <= statement_timestamp() then 'abandoned'
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp() then 'running'
          else tago_arrival_flights.state end,
        outcome_code = case
          when tago_arrival_flights.state = 'running'
            and tago_arrival_flights.lease_expires_at <= statement_timestamp()
          then 'data_unavailable'
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp() then null
          else tago_arrival_flights.outcome_code end,
        retain_until = case
          when tago_arrival_flights.state = 'running'
            and tago_arrival_flights.lease_expires_at <= statement_timestamp()
          then statement_timestamp() + (? * interval '1 millisecond')
          when tago_arrival_flights.state <> 'running'
            and tago_arrival_flights.retain_until <= statement_timestamp()
          then excluded.retain_until else tago_arrival_flights.retain_until end,
        updated_at = statement_timestamp()
      returning state, outcome_code, owner_token, generation
      """;

  private final JdbcTemplate jdbc;

  public JdbcTagoArrivalFlightStore(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc는 필수입니다.");
  }

  @Override
  public TagoArrivalFlightDecision observeOrClaim(
      String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine) {
    try {
      List<TagoArrivalFlightDecision> rows =
          jdbc.query(
              OBSERVE_OR_CLAIM_SQL,
              (resultSet, rowNumber) -> mapDecision(resultSet, fingerprint, proposedOwner),
              fingerprint,
              proposedOwner,
              lease.toMillis(),
              lease.toMillis(),
              quarantine.toMillis());
      if (rows.size() != 1) throw TagoArrivalException.dataUnavailable();
      return rows.getFirst();
    } catch (DataAccessException failure) {
      throw TagoArrivalException.dataUnavailable();
    }
  }

  @Override
  public boolean completeSuccess(TagoArrivalFlightLease lease, Duration retain) {
    return terminal(lease, "succeeded", null, retain);
  }

  @Override
  public boolean completeFailure(
      TagoArrivalFlightLease lease, TagoArrivalException.Code code, Duration retain) {
    return terminal(lease, "failed", databaseCode(code), retain);
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
      TagoArrivalFlightLease lease, String state, String outcome, Duration retain) {
    try {
      return jdbc.update(
              """
              update public.tago_arrival_flights
              set state=?, outcome_code=?,
                  retain_until=statement_timestamp() + (? * interval '1 millisecond'),
                  updated_at=statement_timestamp()
              where fingerprint=? and generation=? and owner_token=? and state='running'
                and lease_expires_at > statement_timestamp()
              """,
              state,
              outcome,
              retain.toMillis(),
              lease.fingerprint(),
              lease.generation(),
              lease.ownerToken())
          == 1;
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
