package com.timingjeju.api.domain.savedplaces.repository;

import com.timingjeju.api.application.pagination.CursorCodec;
import com.timingjeju.api.application.pagination.CursorContext;
import com.timingjeju.api.application.pagination.CursorFilterFingerprint;
import com.timingjeju.api.application.pagination.CursorPosition;
import com.timingjeju.api.application.pagination.CursorSort;
import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException;
import com.timingjeju.api.domain.savedplaces.model.SavedPlace;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCreateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceEtag;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacePatchCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceUpdateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesListResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSavedPlaceRepository implements SavedPlaceRepository {
  private static final String ENDPOINT = "/api/v1/me/saved-places";
  private static final String SELECT_COLUMNS =
      """
      select s.place_id, p.name, p.category, p.region_label, p.thumbnail_url,
             p.recommended_stay_minutes, s.memo, s.tags, s.priority, s.target_day,
             s.created_at, s.updated_at, s.version
      from public.saved_places s join public.tour_places p on p.id=s.place_id
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final CursorCodec cursorCodec;

  public JdbcSavedPlaceRepository(NamedParameterJdbcTemplate jdbc, CursorCodec cursorCodec) {
    this.jdbc = jdbc;
    this.cursorCodec = cursorCodec;
  }

  @Override
  public SavedPlacesListResult list(UUID owner, SavedPlacesQuery query) {
    CursorContext context = context(owner, query);
    CursorPosition after =
        query.cursor() == null ? null : cursorCodec.decode(query.cursor(), context);
    MapSqlParameterSource parameters =
        base(owner)
            .addValue("tag", query.tag(), Types.VARCHAR)
            .addValue("category", query.category(), Types.VARCHAR)
            .addValue("regionCode", query.regionCode(), Types.VARCHAR)
            .addValue("limit", query.size() + 1);
    String keyset = keyset(query.sort(), after, parameters);
    String sql =
        SELECT_COLUMNS
            + """
        where s.user_id=:owner and p.tombstoned_at is null and p.source_deleted_at is null
          and (:tag is null or :tag=any(s.tags))
          and (:category is null or p.category=:category)
          and (:regionCode is null or p.region_code=:regionCode)
        """
            + keyset
            + " order by "
            + order(query.sort())
            + " limit :limit";
    List<RowRepository> rows = jdbc.query(sql, parameters, this::map);
    boolean hasNext = rows.size() > query.size();
    List<RowRepository> page = hasNext ? rows.subList(0, query.size()) : rows;
    String next =
        hasNext ? cursorCodec.encode(context, position(query.sort(), page.getLast())) : null;
    return new SavedPlacesListResult(
        page.stream().map(RowRepository::place).toList(), query.size(), hasNext, next);
  }

  @Override
  public SavedPlaceCreateResult create(UUID owner, String key, SavedPlaceCommand command) {
    String requestHash = requestHash(command);
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
        new MapSqlParameterSource("lockKey", owner + ":" + key),
        resultSet -> {});
    jdbc.update(
        "delete from public.saved_place_idempotency where owner_sub=:owner and idempotency_key=:key and expires_at<=now()",
        base(owner).addValue("key", key));
    List<IdempotencyRowRepository> previous =
        jdbc.query(
            """
        select request_hash, place_id, created, response_etag, response_name, response_category,
               response_region_label, response_thumbnail_url, response_recommended_stay_minutes,
               response_memo, response_tags, response_priority, response_target_day,
               response_saved_at, response_updated_at, response_status,
               response_content_type, response_location, response_body
        from public.saved_place_idempotency
        where owner_sub=:owner and idempotency_key=:key and expires_at>now()
        for update
        """,
            base(owner).addValue("key", key),
            this::mapIdempotency);
    if (!previous.isEmpty()) {
      if (!previous.getFirst().requestHash().equals(requestHash)) {
        throw SavedPlaceException.of("IDEMPOTENCY_PAYLOAD_CONFLICT");
      }
      return new SavedPlaceCreateResult(
          previous.getFirst().row().place(),
          previous.getFirst().etag(),
          true,
          previous.getFirst().created(),
          previous.getFirst().snapshot());
    }

    RowRepository existing = findOrNull(owner, command.placeId());
    if (existing != null) {
      if (!same(existing.place(), command))
        throw SavedPlaceException.of("SAVED_PLACE_ALREADY_EXISTS");
      remember(owner, key, requestHash, existing, false);
      return new SavedPlaceCreateResult(existing.place(), etag(existing), true, false);
    }
    if (!placeExists(command.placeId())) throw SavedPlaceException.of("PLACE_NOT_FOUND");
    boolean inserted = false;
    int insertedCount =
        jdbc.update(
            """
          insert into public.saved_places(user_id,place_id,memo,tags,priority,target_day)
          values (:owner,:placeId,:memo,:tags,:priority,:targetDay)
          on conflict (user_id,place_id) where user_id is not null do nothing
          """,
            commandParameters(owner, command));
    inserted = insertedCount == 1;
    if (!inserted) {
      RowRepository winner = findOrNull(owner, command.placeId());
      if (winner == null || !same(winner.place(), command)) {
        throw SavedPlaceException.of("SAVED_PLACE_ALREADY_EXISTS");
      }
    }
    RowRepository created = find(owner, command.placeId());
    remember(owner, key, requestHash, created, inserted);
    return new SavedPlaceCreateResult(created.place(), etag(created), !inserted, inserted);
  }

  @Override
  public void completeSnapshot(UUID owner, String key, SavedPlaceHttpSnapshot snapshot) {
    int completed =
        jdbc.update(
            """
            update public.saved_place_idempotency
            set response_status=:status, response_content_type=:contentType,
                response_location=:location, response_body=:body
            where owner_sub=:owner and idempotency_key=:key and response_body is null
            """,
            base(owner)
                .addValue("key", key)
                .addValue("status", snapshot.status())
                .addValue("contentType", snapshot.contentType())
                .addValue("location", snapshot.location())
                .addValue("body", snapshot.body()));
    if (completed != 1) throw new IllegalStateException("saved place snapshot completion failed");
  }

  @Override
  public SavedPlaceUpdateResult patch(
      UUID owner, UUID placeId, String ifMatch, SavedPlacePatchCommand command) {
    RowRepository current = find(owner, placeId);
    if (!etag(current).equals(ifMatch))
      throw SavedPlaceException.of("SAVED_PLACE_VERSION_CONFLICT");
    MapSqlParameterSource values =
        base(owner)
            .addValue("placeId", placeId, Types.OTHER)
            .addValue("version", current.version())
            .addValue("memoPresent", command.memo().present())
            .addValue("memo", command.memo().value())
            .addValue("tagsPresent", command.tags().present())
            .addValue(
                "tags",
                command.tags().value() == null
                    ? null
                    : command.tags().value().toArray(String[]::new),
                Types.ARRAY)
            .addValue("priorityPresent", command.priority().present())
            .addValue("priority", command.priority().value())
            .addValue("targetDayPresent", command.targetDay().present())
            .addValue("targetDay", command.targetDay().value());
    int updated =
        jdbc.update(
            """
        update public.saved_places set
          memo=case when :memoPresent then :memo else memo end,
          tags=case when :tagsPresent then cast(:tags as text[]) else tags end,
          priority=case when :priorityPresent then :priority else priority end,
          target_day=case when :targetDayPresent then :targetDay else target_day end,
          version=version+1, updated_at=clock_timestamp()
        where user_id=:owner and place_id=:placeId and version=:version
        """,
            values);
    if (updated != 1) throw SavedPlaceException.of("SAVED_PLACE_VERSION_CONFLICT");
    RowRepository result = find(owner, placeId);
    return new SavedPlaceUpdateResult(result.place(), etag(result));
  }

  @Override
  public boolean delete(UUID owner, UUID placeId) {
    return jdbc.update(
            "delete from public.saved_places where user_id=:owner and place_id=:placeId",
            base(owner).addValue("placeId", placeId, Types.OTHER))
        == 1;
  }

  private RowRepository find(UUID owner, UUID placeId) {
    RowRepository row = findOrNull(owner, placeId);
    if (row == null) throw SavedPlaceException.of("SAVED_PLACE_NOT_FOUND");
    return row;
  }

  private RowRepository findOrNull(UUID owner, UUID placeId) {
    List<RowRepository> rows =
        jdbc.query(
            SELECT_COLUMNS + " where s.user_id=:owner and s.place_id=:placeId",
            base(owner).addValue("placeId", placeId, Types.OTHER),
            this::map);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private boolean placeExists(UUID placeId) {
    Integer count =
        jdbc.queryForObject(
            """
        select count(*) from public.tour_places where id=:placeId and tombstoned_at is null
          and source_deleted_at is null and stale=false and (stale_at is null or stale_at>now())
        """,
            new MapSqlParameterSource("placeId", placeId),
            Integer.class);
    return count != null && count == 1;
  }

  private void remember(UUID owner, String key, String hash, RowRepository row, boolean created) {
    SavedPlace place = row.place();
    jdbc.update(
        """
        insert into public.saved_place_idempotency(
          owner_sub,idempotency_key,request_hash,place_id,created,response_etag,
          response_name,response_category,response_region_label,response_thumbnail_url,
          response_recommended_stay_minutes,response_memo,response_tags,response_priority,
          response_target_day,response_saved_at,response_updated_at,expires_at)
        values (:owner,:key,:hash,:placeId,:created,:etag,:name,:category,:regionLabel,:thumbnailUrl,
          :recommendedStayMinutes,:memo,:tags,:priority,:targetDay,:savedAt,:updatedAt,
          now()+interval '24 hours')
        on conflict (owner_sub,idempotency_key) do nothing
        """,
        base(owner)
            .addValue("key", key)
            .addValue("hash", hash)
            .addValue("placeId", place.placeId(), Types.OTHER)
            .addValue("created", created)
            .addValue("etag", etag(row))
            .addValue("name", place.name())
            .addValue("category", place.category())
            .addValue("regionLabel", place.regionLabel())
            .addValue("thumbnailUrl", place.thumbnailUrl())
            .addValue("recommendedStayMinutes", place.recommendedStayMinutes())
            .addValue("memo", place.memo())
            .addValue("tags", place.tags().toArray(String[]::new), Types.ARRAY)
            .addValue("priority", place.priority())
            .addValue("targetDay", place.targetDay())
            .addValue("savedAt", java.sql.Timestamp.from(place.savedAt()))
            .addValue("updatedAt", java.sql.Timestamp.from(place.updatedAt())));
  }

  private IdempotencyRowRepository mapIdempotency(ResultSet rs, int ignored) throws SQLException {
    Array tags = rs.getArray("response_tags");
    SavedPlace place =
        new SavedPlace(
            rs.getObject("place_id", UUID.class),
            rs.getString("response_name"),
            rs.getString("response_category"),
            rs.getString("response_region_label"),
            rs.getString("response_thumbnail_url"),
            (Integer) rs.getObject("response_recommended_stay_minutes"),
            rs.getString("response_memo"),
            tags == null ? List.of() : List.of((String[]) tags.getArray()),
            rs.getInt("response_priority"),
            (Integer) rs.getObject("response_target_day"),
            rs.getObject("response_saved_at", OffsetDateTime.class).toInstant(),
            rs.getObject("response_updated_at", OffsetDateTime.class).toInstant());
    byte[] responseBody = rs.getBytes("response_body");
    SavedPlaceHttpSnapshot snapshot =
        responseBody == null
            ? null
            : new SavedPlaceHttpSnapshot(
                rs.getInt("response_status"),
                rs.getString("response_content_type"),
                rs.getString("response_location"),
                rs.getString("response_etag"),
                responseBody);
    return new IdempotencyRowRepository(
        rs.getString("request_hash"),
        new RowRepository(place, 0),
        rs.getString("response_etag"),
        rs.getBoolean("created"),
        snapshot);
  }

  private RowRepository map(ResultSet rs, int ignored) throws SQLException {
    Array tags = rs.getArray("tags");
    List<String> tagList = tags == null ? List.of() : List.of((String[]) tags.getArray());
    Instant savedAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
    Instant updatedAt = rs.getObject("updated_at", OffsetDateTime.class).toInstant();
    return new RowRepository(
        new SavedPlace(
            rs.getObject("place_id", UUID.class),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("region_label"),
            rs.getString("thumbnail_url"),
            (Integer) rs.getObject("recommended_stay_minutes"),
            rs.getString("memo"),
            tagList,
            rs.getInt("priority"),
            (Integer) rs.getObject("target_day"),
            savedAt,
            updatedAt),
        rs.getLong("version"));
  }

  private static MapSqlParameterSource base(UUID owner) {
    return new MapSqlParameterSource("owner", owner);
  }

  private static MapSqlParameterSource commandParameters(UUID owner, SavedPlaceCommand command) {
    return base(owner)
        .addValue("placeId", command.placeId(), Types.OTHER)
        .addValue("memo", command.memo())
        .addValue("tags", command.tags().toArray(String[]::new))
        .addValue("priority", command.priority())
        .addValue("targetDay", command.targetDay());
  }

  private static CursorContext context(UUID owner, SavedPlacesQuery query) {
    return new CursorContext(
        ENDPOINT,
        cursorSort(query.sort()),
        CursorFilterFingerprint.sha256(
            Map.of(
                "owner",
                owner.toString(),
                "tag",
                nullToEmpty(query.tag()),
                "category",
                nullToEmpty(query.category()),
                "regionCode",
                nullToEmpty(query.regionCode()),
                "size",
                query.size())));
  }

  private static CursorSort cursorSort(String sort) {
    return "target_day_asc".equals(sort)
        ? CursorSort.asc(sort, "place_id")
        : CursorSort.desc(sort, "place_id");
  }

  private static String keyset(String sort, CursorPosition after, MapSqlParameterSource p) {
    if (after == null) return "";
    String[] values = after.sortValue().split("\\|", -1);
    p.addValue("afterId", UUID.fromString(after.tieBreaker()), Types.OTHER);
    p.addValue("afterSavedAt", OffsetDateTime.parse(values[values.length - 1]));
    if ("priority_desc".equals(sort)) {
      p.addValue("afterPriority", Integer.valueOf(values[0]));
      return " and (s.priority<:afterPriority or (s.priority=:afterPriority and (s.created_at<:afterSavedAt or (s.created_at=:afterSavedAt and s.place_id>:afterId))))";
    }
    if ("target_day_asc".equals(sort)) {
      p.addValue("afterNull", Integer.valueOf(values[0]));
      p.addValue("afterDay", Integer.valueOf(values[1]));
      return " and ((case when s.target_day is null then 1 else 0 end)>:afterNull or ((case when s.target_day is null then 1 else 0 end)=:afterNull and (coalesce(s.target_day,0)>:afterDay or (coalesce(s.target_day,0)=:afterDay and (s.created_at<:afterSavedAt or (s.created_at=:afterSavedAt and s.place_id>:afterId))))))";
    }
    return " and (s.created_at<:afterSavedAt or (s.created_at=:afterSavedAt and s.place_id>:afterId))";
  }

  private static String order(String sort) {
    return switch (sort) {
      case "priority_desc" -> "s.priority desc,s.created_at desc,s.place_id asc";
      case "target_day_asc" -> "s.target_day asc nulls last,s.created_at desc,s.place_id asc";
      default -> "s.created_at desc,s.place_id asc";
    };
  }

  private static CursorPosition position(String sort, RowRepository row) {
    SavedPlace p = row.place();
    String value =
        switch (sort) {
          case "priority_desc" -> p.priority() + "|" + p.savedAt();
          case "target_day_asc" ->
              (p.targetDay() == null ? "1|0|" : "0|" + p.targetDay() + "|") + p.savedAt();
          default -> p.savedAt().toString();
        };
    return new CursorPosition(value, p.placeId().toString());
  }

  private static boolean same(SavedPlace place, SavedPlaceCommand command) {
    return java.util.Objects.equals(place.memo(), command.memo())
        && place.tags().equals(command.tags())
        && place.priority() == command.priority()
        && java.util.Objects.equals(place.targetDay(), command.targetDay());
  }

  private static String etag(RowRepository row) {
    return SavedPlaceEtag.strong(row.place().placeId(), row.place().updatedAt());
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String requestHash(SavedPlaceCommand command) {
    String canonical =
        command.placeId()
            + "\u0000"
            + command.memo()
            + "\u0000"
            + String.join("\u0001", command.tags())
            + "\u0000"
            + command.priority()
            + "\u0000"
            + command.targetDay();
    return sha256(canonical);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record RowRepository(SavedPlace place, long version) {}

  private record IdempotencyRowRepository(
      String requestHash,
      RowRepository row,
      String etag,
      boolean created,
      SavedPlaceHttpSnapshot snapshot) {}
}
