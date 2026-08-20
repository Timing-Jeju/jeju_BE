package com.timingjeju.api.domain.places.repository;

import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPlaceSearchRepository implements PlaceSearchRepository {

  private static final String SELECT =
      """
      with candidates as (
        select p.id as place_id, p.content_id, p.name, p.normalized_name, p.category,
               p.region_code, p.region_label, p.address,
               ST_Y(p.location::geometry) as latitude,
               ST_X(p.location::geometry) as longitude,
               image.thumbnail_url,
               nullif(concat_ws(' · ', nullif(detail.operating_hours_text, ''),
                 nullif(detail.closed_days_text, ''), nullif(detail.parking_text, ''),
                 nullif(detail.admission_fee_text, '')), '') as operations_summary,
               %s as distance_meters,
               case when lower(p.source_provider) in ('tour-api', '한국관광공사')
                    then 'TOUR_API' else 'TIMING_JEJU' end as provider,
               coalesce(p.source_modified_at, p.updated_at) as observed_at,
               p.stale_at as expires_at,
               (p.stale or (p.stale_at is not null and p.stale_at <= now())) as stale,
               (saved.id is not null) as saved,
               saved.memo,
               coalesce(saved.tags, '{}'::text[]) as tags
        from public.tour_places p
        left join public.saved_places saved
          on saved.place_id=p.id and saved.user_id=:userId
        left join public.place_details detail
          on detail.place_id=p.id and detail.tombstoned_at is null
        left join lateral (
          select coalesce(i.thumbnail_url, i.image_url) as thumbnail_url
          from public.place_images i
          where i.place_id=p.id and i.tombstoned_at is null
          order by i.display_order asc, i.id asc
          limit 1
        ) image on true
        where p.source_deleted_at is null
          and p.content_id is not null and btrim(p.content_id) <> ''
          and p.region_code is not null
          and (:category is null or p.category=:category)
          and (:regionCode is null or p.region_code=:regionCode)
          and (:savedOnly=false or saved.id is not null)
          and (:queryPattern is null or p.normalized_name like :queryPattern escape '\\'
            or exists (
              select 1 from public.place_aliases alias
              where alias.place_id=p.id and alias.tombstoned_at is null
                and alias.normalized_alias like :queryPattern escape '\\'
            ))
          %s
      )
      select * from candidates
      %s
      order by %s
      limit :limit
      """;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcPlaceSearchRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<PlaceSearchRow> search(
      PlacesListQuery query, PlaceSearchPosition after, Optional<UUID> currentUserId) {
    boolean nearby = query.nearby();
    String distance =
        nearby
            ? "round(ST_Distance(p.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography))::bigint"
            : "null::bigint";
    String geo =
        nearby
            ? "and ST_DWithin(p.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)"
            : "";
    String keyset = keysetClause(nearby, after);
    String order =
        nearby
            ? "distance_meters asc, normalized_name asc, place_id asc"
            : "normalized_name asc, place_id asc";
    String sql = SELECT.formatted(distance, geo, keyset, order);
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("userId", currentUserId.orElse(null), Types.OTHER)
            .addValue("category", query.category(), Types.VARCHAR)
            .addValue("regionCode", query.regionCode(), Types.VARCHAR)
            .addValue("savedOnly", query.savedOnly())
            .addValue("queryPattern", searchPattern(query.query()), Types.VARCHAR)
            .addValue("limit", query.size() + 1);
    if (nearby) {
      parameters
          .addValue("lat", query.lat())
          .addValue("lng", query.lng())
          .addValue("radiusMeters", query.radiusMeters());
    }
    if (after != null) {
      parameters
          .addValue("afterName", after.normalizedName())
          .addValue("afterId", after.placeId(), Types.OTHER);
      if (nearby) {
        parameters.addValue("afterDistance", after.distanceMeters());
      }
    }
    return jdbc.query(sql, parameters, this::map);
  }

  private static String keysetClause(boolean nearby, PlaceSearchPosition after) {
    if (after == null) {
      return "";
    }
    return nearby
        ? "where (distance_meters, normalized_name, place_id) > (:afterDistance, :afterName, :afterId)"
        : "where (normalized_name, place_id) > (:afterName, :afterId)";
  }

  private PlaceSearchRow map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new PlaceSearchRow(
        resultSet.getObject("place_id", UUID.class),
        resultSet.getString("content_id"),
        resultSet.getString("name"),
        resultSet.getString("normalized_name"),
        resultSet.getString("category"),
        resultSet.getString("region_code"),
        resultSet.getString("region_label"),
        resultSet.getString("address"),
        resultSet.getDouble("latitude"),
        resultSet.getDouble("longitude"),
        resultSet.getString("thumbnail_url"),
        resultSet.getString("operations_summary"),
        resultSet.getObject("distance_meters", Long.class),
        resultSet.getString("provider"),
        instant(resultSet, "observed_at"),
        instant(resultSet, "expires_at"),
        resultSet.getBoolean("stale"),
        resultSet.getBoolean("saved"),
        resultSet.getString("memo"),
        textArray(resultSet.getArray("tags")));
  }

  static String searchPattern(String query) {
    if (query == null) {
      return null;
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static Instant instant(ResultSet resultSet, String column) throws SQLException {
    java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static List<String> textArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object value = array.getArray();
    if (value instanceof String[] strings) {
      return List.of(strings);
    }
    Object[] values = (Object[]) value;
    List<String> result = new ArrayList<>(values.length);
    for (Object item : values) {
      result.add(String.valueOf(item));
    }
    return List.copyOf(result);
  }
}
