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
    return query(Set.copyOf(canonicalIds), now, false);
  }

  @Override
  public GenerationPlaceBindings resolveFactIds(Set<String> factIds, Instant now) {
    var contentIds = new java.util.HashSet<String>();
    for (var factId : factIds) {
      if (!GenerationPlaceBindings.approvedFactId(factId))
        throw GenerationException.inputUnavailable();
      contentIds.add(factId);
    }
    return query(contentIds, now, true);
  }

  private GenerationPlaceBindings query(Set<?> ids, Instant now, boolean byContentId) {
    if (ids.isEmpty()) return new GenerationPlaceBindings(List.of());
    try {
      var places =
          jdbc.query(
              """
          select id,content_id,name,source_id from (
          select p.id,p.content_id,p.name,'tourapi.place' as source_id, 'tourapi.place:'||p.content_id as fact_id from public.tour_places p
          join public.data_import_runs r on r.id=p.import_run_id
          where r.source_kind='tour_api' and r.status='succeeded'
            and p.content_id is not null and not p.stale
            and (p.stale_at is null or p.stale_at>:now)
            and p.tombstoned_at is null and p.source_deleted_at is null
          union all
          select a.id,a.external_id,a.name,a.source_id,a.source_id||':'||a.external_id
          from public.approved_airport_places a where a.expires_at>:now
          ) approved where %s in (:ids) order by id
          """
                  .formatted(byContentId ? "fact_id" : "id"),
              Map.of("ids", ids, "now", Timestamp.from(now)),
              (row, n) ->
                  new GenerationPlaceBindings.Place(
                      row.getObject("id", UUID.class),
                      row.getString("content_id"),
                      row.getString("name"),
                      row.getString("source_id")));
      if (places.size() != ids.size()) throw GenerationException.inputUnavailable();
      return new GenerationPlaceBindings(places);
    } catch (DataAccessException failure) {
      throw GenerationException.inputUnavailable();
    }
  }
}
