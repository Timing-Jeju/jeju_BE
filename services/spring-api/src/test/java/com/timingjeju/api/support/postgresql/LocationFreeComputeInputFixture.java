package com.timingjeju.api.support.postgresql;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 현재 스키마의 정상 feasibility fixture를 호출자의 부모 생성 transaction 안에서 연결한다. */
public final class LocationFreeComputeInputFixture {
  private LocationFreeComputeInputFixture() {}

  public static String attachFeasibilityInput(JdbcTemplate jdbc, UUID runId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("정상 run과 input fixture는 같은 transaction이어야 합니다.");
    }
    String hash =
        jdbc.queryForObject(
            """
            select public.compute_command_input_hash(
              run.run_type::text,2::smallint,run.contract_version::text,run.algorithm_version::text,
              run.schedule_version_id::uuid,'{"refreshExternalFacts":false}'::jsonb)
            from public.compute_runs run join public.trip_plans trip on trip.id=run.trip_plan_id
            where run.id=? and run.run_type='feasibility' and trip.user_id is not null
            """,
            String.class,
            runId);
    jdbc.update("update public.compute_runs set input_hash=? where id=?", hash, runId);
    jdbc.update(
        """
        insert into public.compute_run_inputs
          (compute_run_id,owner_user_id,trip_plan_id,base_schedule_version_id,run_type,
           schema_version,contract_version,algorithm_version,structured_input,command_input_hash)
        select run.id,trip.user_id,run.trip_plan_id,run.schedule_version_id,run.run_type,
               2,run.contract_version,run.algorithm_version,'{"refreshExternalFacts":false}'::jsonb,
               run.input_hash
        from public.compute_runs run join public.trip_plans trip on trip.id=run.trip_plan_id
        where run.id=?
        """,
        runId);
    return hash;
  }
}
