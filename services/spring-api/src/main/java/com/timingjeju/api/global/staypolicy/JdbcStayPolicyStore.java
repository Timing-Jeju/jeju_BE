package com.timingjeju.api.global.staypolicy;

import com.timingjeju.api.application.staypolicy.RecommendedStay;
import com.timingjeju.api.application.staypolicy.RecommendedStaySource;
import com.timingjeju.api.application.staypolicy.StayPolicyCandidate;
import com.timingjeju.api.application.staypolicy.StayPolicyLookup;
import com.timingjeju.api.application.staypolicy.StayPolicyPublicationStore;
import com.timingjeju.api.application.staypolicy.StayPolicyScope;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetCatalog;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetValidation;
import com.timingjeju.api.application.staypolicy.ValidatedStayPolicyPayload;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStayPolicyStore
    implements StayPolicyTargetCatalog, StayPolicyPublicationStore, StayPolicyLookup {

  private static final long PUBLICATION_LOCK = 65_000_065L;

  private final JdbcTemplate jdbc;

  public JdbcStayPolicyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public StayPolicyTargetValidation validateTargets(Set<String> categories, Set<UUID> placeIds) {
    return new StayPolicyTargetValidation(
        liveValues(
            "select distinct category from public.tour_places where not stale and source_deleted_at is null and category in (",
            categories,
            String.class),
        liveValues(
            "select id from public.tour_places where not stale and source_deleted_at is null and id in (",
            placeIds,
            UUID.class));
  }

  @Override
  @Transactional
  public void publish(ValidatedStayPolicyPayload payload, Instant importedAt) {
    jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, PUBLICATION_LOCK);
    String active = activeVersion().orElse(null);
    if (!Objects.equals(payload.expectedActiveVersion(), active)) {
      throw StayPolicyPublicationException.stale(payload.expectedActiveVersion(), active);
    }
    Optional<String> existingHash = payloadHash(payload.version());
    if (existingHash.isPresent()) {
      if (Objects.equals(payload.version(), active)
          && existingHash.orElseThrow().equals(payload.payloadHash())) {
        return;
      }
      throw StayPolicyPublicationException.collision(payload.version());
    }
    jdbc.update(
        """
        insert into public.place_stay_policy_versions (
          version, status, payload_hash, effective_at, imported_at
        ) values (?, 'draft', ?, ?, ?)
        """,
        payload.version(),
        payload.payloadHash(),
        Timestamp.from(payload.effectiveAt()),
        Timestamp.from(importedAt));
    for (StayPolicyCandidate policy : payload.policies()) {
      jdbc.update(
          """
          insert into public.place_stay_policies (
            version, scope, category, place_id, minutes, source, updated_at
          ) values (?, ?, ?, ?, ?, 'app_curation', ?)
          """,
          payload.version(),
          databaseScope(policy.scope()),
          policy.category(),
          policy.placeId(),
          policy.minutes(),
          Timestamp.from(importedAt));
    }
    if (active != null) {
      requireOne(
          jdbc.update(
              "update public.place_stay_policy_versions set status='retired' where version=? and status='active'",
              active));
    }
    requireOne(
        jdbc.update(
            "update public.place_stay_policy_versions set status='active' where version=? and status='draft'",
            payload.version()));
  }

  @Override
  public Optional<RecommendedStay> findActive(UUID placeId, String category) {
    List<RecommendedStay> values =
        jdbc.query(
            """
            select p.minutes, p.scope, v.version, v.effective_at, p.updated_at
            from public.place_stay_policy_versions v
            join public.place_stay_policies p on p.version = v.version
            where v.status = 'active'
              and ((p.scope = 'place_override' and p.place_id = ?)
                or (p.scope = 'category_default' and p.category = ?))
            order by case p.scope when 'place_override' then 0 else 1 end
            limit 1
            """,
            (resultSet, rowNumber) ->
                new RecommendedStay(
                    resultSet.getInt("minutes"),
                    "place_override".equals(resultSet.getString("scope"))
                        ? RecommendedStaySource.PLACE_OVERRIDE
                        : RecommendedStaySource.CATEGORY_DEFAULT,
                    resultSet.getString("version"),
                    resultSet.getTimestamp("effective_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant()),
            placeId,
            category);
    return values.stream().findFirst();
  }

  private Optional<String> activeVersion() {
    return jdbc
        .query(
            "select version from public.place_stay_policy_versions where status='active'",
            (resultSet, rowNumber) -> resultSet.getString(1))
        .stream()
        .findFirst();
  }

  private Optional<String> payloadHash(String version) {
    return jdbc
        .query(
            "select payload_hash from public.place_stay_policy_versions where version=?",
            (resultSet, rowNumber) -> resultSet.getString(1),
            version)
        .stream()
        .findFirst();
  }

  private <T> Set<T> liveValues(String sqlPrefix, Set<T> requested, Class<T> type) {
    if (requested.isEmpty()) {
      return Set.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
    List<T> found = jdbc.queryForList(sqlPrefix + placeholders + ")", type, requested.toArray());
    return Set.copyOf(new HashSet<>(found));
  }

  private static String databaseScope(StayPolicyScope scope) {
    return switch (scope) {
      case CATEGORY_DEFAULT -> "category_default";
      case PLACE_OVERRIDE -> "place_override";
    };
  }

  private static void requireOne(int changed) {
    if (changed != 1) {
      throw new IllegalStateException("Stay policy publication lost its active-version fence");
    }
  }
}
