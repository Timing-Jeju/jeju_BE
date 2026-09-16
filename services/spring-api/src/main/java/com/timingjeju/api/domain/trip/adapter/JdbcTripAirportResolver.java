package com.timingjeju.api.domain.trip.adapter;

import com.timingjeju.api.application.trip.TripAirportResolver;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTripAirportResolver implements TripAirportResolver {
  private final JdbcTemplate jdbc;
  private final String airportId;

  public JdbcTripAirportResolver(
      JdbcTemplate jdbc,
      @Value("${app.schedule-generation.approved-airport-place-id:}") String airportId) {
    this.jdbc = jdbc;
    this.airportId = airportId;
  }

  @Override
  public Optional<UUID> findApproved() {
    UUID id;
    try {
      id = UUID.fromString(airportId);
      if (!id.toString().equals(airportId)) return Optional.empty();
    } catch (IllegalArgumentException failure) {
      return Optional.empty();
    }
    return jdbc
        .queryForList(
            """
        select id from public.tour_places where id=? and name='제주국제공항'
          and not stale
          and ((content_id is not null and exists(select 1 from public.data_import_runs r where r.id=tour_places.import_run_id
            and r.source_kind='tour_api' and r.status='succeeded'))
            or exists(select 1 from public.approved_airport_places a where a.id=tour_places.id))
          and (stale_at is null or stale_at>statement_timestamp())
          and tombstoned_at is null and source_deleted_at is null for share
        """,
            UUID.class,
            id)
        .stream()
        .findFirst();
  }
}
