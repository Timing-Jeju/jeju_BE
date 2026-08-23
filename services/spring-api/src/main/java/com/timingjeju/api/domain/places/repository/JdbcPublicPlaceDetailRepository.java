package com.timingjeju.api.domain.places.repository;

import com.timingjeju.api.domain.places.config.PlaceNearbyStopsProperties;
import com.timingjeju.api.domain.places.exception.PlaceDetailUnavailableException;
import com.timingjeju.api.domain.places.model.PlaceDetailImageRow;
import com.timingjeju.api.domain.places.model.PlaceDetailNearbyStopRow;
import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPublicPlaceDetailRepository implements PlaceDetailRepository {

  static final String SELECT =
      """
      select p.id as place_id, p.content_id, p.name, p.category, p.region_code,
             p.region_label, p.address,
             ST_Y(p.location::geometry) as latitude,
             ST_X(p.location::geometry) as longitude,
             p.overview,
             detail.phone, detail.homepage_url, detail.operating_hours_text,
             detail.closed_days_text, detail.parking_text, detail.admission_fee_text,
             image.id as image_id, image.image_url, image.thumbnail_url,
             case when lower(image.source_provider) in ('tour-api', '한국관광공사')
                  then 'TOUR_API' else 'TIMING_JEJU' end as image_provider,
             coalesce(image.source_modified_at, image.last_seen_at, image.created_at)
               as image_observed_at,
             p.stale_at as image_expires_at,
             coalesce(p.stale, false)
               or (p.stale_at is not null and p.stale_at <= now())
               or (image.stale_at is not null and image.stale_at <= now()) as image_stale,
             (saved.id is not null) as saved, saved.memo,
             coalesce(saved.tags, '{}'::text[]) as tags
      from public.tour_places p
      left join public.place_details detail
        on detail.place_id=p.id and detail.tombstoned_at is null
      left join lateral (
        select candidate.*
        from public.place_images candidate
        where candidate.place_id=p.id and candidate.tombstoned_at is null
        order by candidate.display_order asc nulls last, candidate.id asc
        limit 20
      ) image on true
      left join public.saved_places saved
        on saved.place_id=p.id and saved.user_id=:userId
      where p.id=:placeId
        and p.source_deleted_at is null
        and p.tombstoned_at is null
        and p.stale = false
        and (p.stale_at is null or p.stale_at > now())
        and p.content_id is not null and btrim(p.content_id) <> ''
        and p.region_code is not null and btrim(p.region_code) <> ''
      order by image.display_order asc nulls last, image.id asc nulls last
      """;

  static final String SELECT_NEARBY_STOPS =
      """
      select link.stop_id, stop.node_name as stop_name,
             link.distance_meters, link.walk_minutes, link.link_method,
             link.source_provider,
             link.observed_at,
             least(link.expires_at, coalesce(stop.stale_at, link.expires_at))
               as effective_expires_at,
             least(link.expires_at, coalesce(stop.stale_at, link.expires_at)) > :now
               as fresh
      from public.place_stop_links link
      join public.bus_stops stop on stop.id=link.stop_id
      where link.place_id=:placeId
        and link.enabled
        and link.tombstoned_at is null
        and stop.tombstoned_at is null
        and stop.source_deleted_at is null
        and link.distance_meters <= :maxDistanceMeters
      order by fresh desc, link.distance_meters asc,
               link.walk_minutes asc nulls last, link.stop_id asc
      limit 5
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final PlaceNearbyStopsProperties nearbyStopsProperties;
  private final Clock clock;

  public JdbcPublicPlaceDetailRepository(
      NamedParameterJdbcTemplate jdbc,
      PlaceNearbyStopsProperties nearbyStopsProperties,
      Clock clock) {
    this.jdbc = jdbc;
    this.nearbyStopsProperties = nearbyStopsProperties;
    this.clock = clock;
  }

  @Override
  public Optional<PlaceDetailSnapshot> find(UUID placeId, Optional<UUID> currentUserId) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("placeId", placeId, Types.OTHER)
            .addValue("userId", currentUserId.orElse(null), Types.OTHER);
    try {
      List<PlaceDetailRowRepository> rows = jdbc.query(SELECT, parameters, this::map);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      PlaceDetailRowRepository first = rows.getFirst();
      List<PlaceDetailImageRow> images =
          rows.stream().map(PlaceDetailRowRepository::image).flatMap(Optional::stream).toList();
      return Optional.of(first.snapshot(images, nearbyStops(placeId)));
    } catch (DataAccessException | IllegalArgumentException failure) {
      throw new PlaceDetailUnavailableException();
    }
  }

  private List<PlaceDetailNearbyStopRow> nearbyStops(UUID placeId) {
    return jdbc.query(
        SELECT_NEARBY_STOPS,
        nearbyStopParameters(placeId),
        (resultSet, rowNumber) ->
            new PlaceDetailNearbyStopRow(
                resultSet.getObject("stop_id", UUID.class),
                resultSet.getString("stop_name"),
                resultSet.getLong("distance_meters"),
                resultSet.getObject("walk_minutes", Integer.class),
                resultSet.getString("link_method"),
                resultSet.getString("source_provider"),
                instant(resultSet, "observed_at"),
                instant(resultSet, "effective_expires_at"),
                !resultSet.getBoolean("fresh")));
  }

  MapSqlParameterSource nearbyStopParameters(UUID placeId) {
    return new MapSqlParameterSource()
        .addValue("placeId", placeId, Types.OTHER)
        .addValue("maxDistanceMeters", nearbyStopsProperties.maxDistanceMeters())
        .addValue(
            "now",
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private PlaceDetailRowRepository map(ResultSet resultSet, int rowNumber) throws SQLException {
    UUID imageId = resultSet.getObject("image_id", UUID.class);
    Optional<PlaceDetailImageRow> image =
        imageId == null
            ? Optional.empty()
            : Optional.of(
                new PlaceDetailImageRow(
                    imageId,
                    resultSet.getString("image_url"),
                    resultSet.getString("thumbnail_url"),
                    resultSet.getString("image_provider"),
                    instant(resultSet, "image_observed_at"),
                    instant(resultSet, "image_expires_at"),
                    resultSet.getBoolean("image_stale")));
    return new PlaceDetailRowRepository(
        resultSet.getObject("place_id", UUID.class),
        resultSet.getString("content_id"),
        resultSet.getString("name"),
        resultSet.getString("category"),
        resultSet.getString("region_code"),
        resultSet.getString("region_label"),
        resultSet.getString("address"),
        resultSet.getDouble("latitude"),
        resultSet.getDouble("longitude"),
        resultSet.getString("overview"),
        resultSet.getString("phone"),
        resultSet.getString("homepage_url"),
        resultSet.getString("operating_hours_text"),
        resultSet.getString("closed_days_text"),
        resultSet.getString("parking_text"),
        resultSet.getString("admission_fee_text"),
        image,
        resultSet.getBoolean("saved"),
        resultSet.getString("memo"),
        textArray(resultSet.getArray("tags")));
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

  private record PlaceDetailRowRepository(
      UUID placeId,
      String contentId,
      String name,
      String category,
      String regionCode,
      String regionLabel,
      String address,
      double latitude,
      double longitude,
      String overview,
      String phone,
      String homepageUrl,
      String operatingHoursText,
      String closedDaysText,
      String parkingText,
      String admissionFeeText,
      Optional<PlaceDetailImageRow> image,
      boolean saved,
      String memo,
      List<String> tags) {

    PlaceDetailSnapshot snapshot(
        List<PlaceDetailImageRow> images, List<PlaceDetailNearbyStopRow> nearbyStops) {
      return new PlaceDetailSnapshot(
          placeId,
          contentId,
          name,
          category,
          regionCode,
          regionLabel,
          address,
          latitude,
          longitude,
          overview,
          phone,
          homepageUrl,
          operatingHoursText,
          closedDaysText,
          parkingText,
          admissionFeeText,
          images,
          nearbyStops,
          saved,
          memo,
          tags);
    }
  }
}
