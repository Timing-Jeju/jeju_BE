package com.timingjeju.api.global.legal;

import com.timingjeju.api.application.legal.ConsentDecision;
import com.timingjeju.api.application.legal.ConsentUpdateResult;
import com.timingjeju.api.application.legal.LegalDocument;
import com.timingjeju.api.application.legal.LegalDocumentSelection;
import com.timingjeju.api.application.legal.LegalDocumentStore;
import com.timingjeju.api.application.legal.LegalProfileException;
import com.timingjeju.api.application.legal.UserConsentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLegalConsentStore implements LegalDocumentStore, UserConsentStore {

  private static final String EFFECTIVE_CANDIDATES_SQL =
      """
      select id, document_type, locale, version, title, content_url, required, effective_at
      from public.legal_documents
      where locale in (?, 'ko-KR')
        and effective_at <= ?
        and (retired_at is null or retired_at > ?)
        and document_type in ('terms', 'privacy', 'location')
      order by document_type, effective_at desc, version desc, id
      """;

  private static final String LOCK_PROFILE_SQL =
      """
      select id from public.user_profiles
      where id = ? and status <> 'deleted'
      for update
      """;

  private static final String UPSERT_CONSENT_SQL =
      """
      insert into public.user_consents (
        user_id, legal_document_id, agreed, agreed_at, withdrawn_at, source
      ) values (
        ?::uuid,
        ?::uuid,
        ?::boolean,
        ?::timestamptz,
        case when ?::boolean then null::timestamptz else ?::timestamptz end,
        'web'
      )
      on conflict (user_id, legal_document_id) do update
      set agreed = case
            when user_consents.agreed = excluded.agreed then user_consents.agreed
            when excluded.agreed_at > user_consents.agreed_at then excluded.agreed
            when excluded.agreed_at = user_consents.agreed_at
              then user_consents.agreed and excluded.agreed
            else user_consents.agreed
          end,
          agreed_at = case
            when user_consents.agreed = excluded.agreed then user_consents.agreed_at
            else greatest(user_consents.agreed_at, excluded.agreed_at)
          end,
          withdrawn_at = case
            when user_consents.agreed = excluded.agreed then user_consents.withdrawn_at
            when excluded.agreed_at > user_consents.agreed_at then excluded.withdrawn_at
            when excluded.agreed_at < user_consents.agreed_at then user_consents.withdrawn_at
            when user_consents.agreed and excluded.agreed then null
            else coalesce(
              user_consents.withdrawn_at, excluded.withdrawn_at, excluded.agreed_at
            )
          end
      """;

  private static final String REQUIRED_AGREEMENT_COUNT_SQL =
      """
      select count(*)
      from public.user_consents
      where user_id = ? and agreed = true and legal_document_id in (%s)
      """;

  private static final String UPDATED_AT_SQL =
      """
      select max(agreed_at)
      from public.user_consents
      where user_id = ? and legal_document_id in (%s)
      """;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcLegalConsentStore(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public List<LegalDocument> findEffectiveCandidates(String locale, Instant evaluatedAt) {
    try {
      return queryCandidates(locale, evaluatedAt);
    } catch (DataAccessException failure) {
      throw LegalProfileException.dataUnavailable();
    }
  }

  @Override
  @Transactional
  public ConsentUpdateResult updateRequiredConsents(
      UUID userId, String locale, List<ConsentDecision> decisions, Instant evaluatedAt) {
    try {
      if (jdbc.queryForList(LOCK_PROFILE_SQL, UUID.class, userId).size() != 1) {
        throw LegalProfileException.dataUnavailable();
      }
      Instant activeDocumentEvaluatedAt = clock.instant();
      List<LegalDocument> latest =
          LegalDocumentSelection.latest(queryCandidates(locale, activeDocumentEvaluatedAt), locale);
      Map<UUID, LegalDocument> byId = new HashMap<>();
      latest.forEach(document -> byId.put(document.documentId(), document));
      for (ConsentDecision decision : decisions) {
        LegalDocument document = byId.get(decision.documentId());
        if (document == null) {
          throw LegalProfileException.invalidRequest();
        }
        if (document.required() && !decision.agreed()) {
          throw LegalProfileException.consentRequired();
        }
      }
      for (ConsentDecision decision : decisions) {
        Timestamp timestamp = Timestamp.from(evaluatedAt);
        jdbc.update(
            UPSERT_CONSENT_SQL,
            userId,
            decision.documentId(),
            decision.agreed(),
            timestamp,
            decision.agreed(),
            timestamp);
      }
      UUID[] requiredIds =
          latest.stream()
              .filter(LegalDocument::required)
              .map(LegalDocument::documentId)
              .toArray(UUID[]::new);
      if (requiredIds.length > 0 && agreementCount(userId, requiredIds) != requiredIds.length) {
        throw LegalProfileException.consentRequired();
      }
      UUID[] updatedIds = decisions.stream().map(ConsentDecision::documentId).toArray(UUID[]::new);
      Instant updatedAt = readUpdatedAt(userId, updatedIds);
      return new ConsentUpdateResult(true, updatedAt);
    } catch (LegalProfileException failure) {
      throw failure;
    } catch (DataAccessException failure) {
      throw LegalProfileException.dataUnavailable();
    }
  }

  static String contractSql() {
    return String.join(
        "\n",
        EFFECTIVE_CANDIDATES_SQL,
        LOCK_PROFILE_SQL,
        UPSERT_CONSENT_SQL,
        REQUIRED_AGREEMENT_COUNT_SQL,
        UPDATED_AT_SQL);
  }

  static String profileLockSql() {
    return LOCK_PROFILE_SQL;
  }

  static String effectiveCandidatesSql() {
    return EFFECTIVE_CANDIDATES_SQL;
  }

  static String upsertConsentSql() {
    return UPSERT_CONSENT_SQL;
  }

  static String requiredAgreementCountSql(int size) {
    return REQUIRED_AGREEMENT_COUNT_SQL.formatted(placeholders(size));
  }

  static String updatedAtSql(int size) {
    return UPDATED_AT_SQL.formatted(placeholders(size));
  }

  private List<LegalDocument> queryCandidates(String locale, Instant evaluatedAt) {
    Timestamp timestamp = Timestamp.from(evaluatedAt);
    return jdbc.query(
        EFFECTIVE_CANDIDATES_SQL,
        (resultSet, rowNumber) -> document(resultSet),
        locale,
        timestamp,
        timestamp);
  }

  private int agreementCount(UUID userId, UUID[] ids) {
    Integer count =
        jdbc.queryForObject(
            requiredAgreementCountSql(ids.length), Integer.class, parameters(userId, ids));
    return count == null ? 0 : count;
  }

  private Instant readUpdatedAt(UUID userId, UUID[] ids) {
    Timestamp updatedAt =
        jdbc.queryForObject(updatedAtSql(ids.length), Timestamp.class, parameters(userId, ids));
    if (updatedAt == null) {
      throw LegalProfileException.dataUnavailable();
    }
    return updatedAt.toInstant();
  }

  private static String placeholders(int size) {
    return String.join(", ", java.util.Collections.nCopies(size, "?"));
  }

  private static Object[] parameters(UUID userId, UUID[] ids) {
    Object[] parameters = new Object[ids.length + 1];
    parameters[0] = userId;
    System.arraycopy(ids, 0, parameters, 1, ids.length);
    return parameters;
  }

  static LegalDocument document(ResultSet resultSet) throws SQLException {
    return new LegalDocument(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("document_type"),
        resultSet.getString("locale"),
        resultSet.getString("version"),
        resultSet.getString("title"),
        resultSet.getString("content_url"),
        resultSet.getBoolean("required"),
        resultSet.getTimestamp("effective_at").toInstant());
  }
}
