package com.timingjeju.api.global.timetable;

import com.timingjeju.api.application.timetable.TimetableCatalog;
import com.timingjeju.api.application.timetable.TimetableCatalogValidation;
import com.timingjeju.api.application.timetable.TimetableEntryCandidate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcTimetableCatalog implements TimetableCatalog {
  private final JdbcTemplate jdbc;

  public JdbcTimetableCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public TimetableCatalogValidation validate(List<TimetableEntryCandidate> entries) {
    for (TimetableEntryCandidate entry : entries) {
      Integer exact =
          jdbc.queryForObject(
              """
              select count(*)
              from public.route_stops route_stop
              join public.bus_routes route on route.id=route_stop.route_id
              join public.bus_stops stop on stop.id=route_stop.stop_id
              where route_stop.route_id=? and route_stop.direction_key=? and route_stop.stop_id=?
                and route_stop.source_provider=? and route_stop.city_code=?
                and route.source_provider=route_stop.source_provider
                and route.city_code=route_stop.city_code
                and stop.source_provider=route_stop.source_provider
                and stop.city_code=route_stop.city_code
                and route_stop.tombstoned_at is null
              """,
              Integer.class,
              entry.routeId(),
              entry.directionKey(),
              entry.stopId(),
              entry.routeSourceProvider(),
              entry.routeCityCode());
      if (exact != null && exact == 1) continue;
      Integer identities =
          jdbc.queryForObject(
              "select count(*) from public.route_stops where route_id=? and direction_key=? and stop_id=?",
              Integer.class,
              entry.routeId(),
              entry.directionKey(),
              entry.stopId());
      if (identities == null || identities == 0) return TimetableCatalogValidation.MISSING;
      if (identities > 1) return TimetableCatalogValidation.AMBIGUOUS;
      return TimetableCatalogValidation.MISMATCH;
    }
    return TimetableCatalogValidation.VALID;
  }
}
