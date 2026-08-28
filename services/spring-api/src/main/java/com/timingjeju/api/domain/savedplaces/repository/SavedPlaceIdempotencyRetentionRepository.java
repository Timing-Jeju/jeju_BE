package com.timingjeju.api.domain.savedplaces.repository;

import com.timingjeju.api.application.retention.SavedPlaceRetentionTask;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class SavedPlaceIdempotencyRetentionRepository implements SavedPlaceRetentionTask {
  private static final int BATCH_SIZE = 100;
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate requiresNew;

  public SavedPlaceIdempotencyRetentionRepository(
      NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public int drain(int maxBatches) {
    if (maxBatches < 1 || maxBatches > 10) {
      throw new IllegalArgumentException("retention maxBatches must be between 1 and 10");
    }
    int deleted = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      Map.Entry<Integer, Boolean> result = purgeBatch();
      deleted += result.getKey();
      if (!result.getValue()) {
        break;
      }
    }
    return deleted;
  }

  private Map.Entry<Integer, Boolean> purgeBatch() {
    Map.Entry<Integer, Boolean> result =
        requiresNew.execute(
            ignored -> {
              int markerCount =
                  jdbc.update(
                      """
                    with expired as (
                      select owner_sub,idempotency_key
                      from public.saved_place_idempotency
                      where expires_at<=now()
                      order by expires_at
                      for update skip locked
                      limit 100
                    )
                    delete from public.saved_place_idempotency marker
                    using expired
                    where marker.owner_sub=expired.owner_sub
                      and marker.idempotency_key=expired.idempotency_key
                    """,
                      new MapSqlParameterSource());
              int auditCount =
                  jdbc.update(
                      """
                    with expired as (
                      select saved_place_id
                      from public.saved_places_backfill_audit
                      where purge_after<=now()
                      order by purge_after
                      for update skip locked
                      limit 100
                    )
                    delete from public.saved_places_backfill_audit audit
                    using expired
                    where audit.saved_place_id=expired.saved_place_id
                    """,
                      new MapSqlParameterSource());
              return Map.entry(
                  markerCount + auditCount, markerCount == BATCH_SIZE || auditCount == BATCH_SIZE);
            });
    return result == null ? Map.entry(0, false) : result;
  }
}
