package com.timingjeju.api.domain.generation.adapter;

import com.timingjeju.api.application.generation.GenerationException;
import com.timingjeju.api.application.generation.GenerationPlaceBindings;
import com.timingjeju.api.application.generation.GenerationPlaceResolver;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Trip은 다시 읽지 않고 승인된 공개 장소만 단일 SELECT로 매핑한다. */
@Repository
public class JdbcGenerationPlaceResolver implements GenerationPlaceResolver {
  private final NamedParameterJdbcTemplate jdbc;

  public JdbcGenerationPlaceResolver(JdbcTemplate jdbc) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbc);
  }

  @Override
  public GenerationPlaceBindings resolve(Set<UUID> canonicalIds, Instant now) {
    var ids = Set.copyOf(canonicalIds);
    if (ids.isEmpty()) return new GenerationPlaceBindings(List.of());
    try {
      var places =
          jdbc.query(
              """
          select p.id,p.content_id,p.name from public.tour_places p
          join public.data_import_runs r on r.id=p.import_run_id
          where p.id in (:ids) and r.source_kind='tour_api' and r.status='succeeded'
            and p.content_id is not null and not p.stale
            and (p.stale_at is null or p.stale_at>:now)
            and p.tombstoned_at is null and p.source_deleted_at is null
          order by p.id
          """,
              Map.of("ids", ids, "now", Timestamp.from(now)),
              (row, n) ->
                  new GenerationPlaceBindings.Place(
                      row.getObject("id", UUID.class),
                      row.getString("content_id"),
                      row.getString("name")));
      if (places.size() != ids.size()) throw GenerationException.inputUnavailable();
      return new GenerationPlaceBindings(places);
    } catch (DataAccessException failure) {
      throw GenerationException.inputUnavailable();
    }
  }
}
