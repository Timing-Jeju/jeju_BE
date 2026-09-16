package com.timingjeju.api.support.postgresql;

import static org.assertj.core.api.Assertions.*;

import com.timingjeju.api.domain.generation.adapter.JdbcGenerationPlaceResolver;
import com.timingjeju.api.domain.trip.adapter.JdbcTripAirportResolver;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OfficialAirportBindingIntegrationTest extends PostgreSqlRepositoryIntegrationTestSupport {
  @Autowired JdbcTemplate jdbc;

  @Test
  void 공식공항_UUID와_factID를_연결하고_출처불일치와_만료는_거부한다() {
    UUID run = UUID.randomUUID();
    UUID place = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    jdbc.update(
        """
      insert into public.data_import_runs(id,source_kind,source_name,source_operation,data_version,status,source_provider,source_service,finished_at,metadata)
      values (?,'admin_upload','kac.airport','15002851','test','succeeded','한국공항공사','15002851',now(),
      jsonb_build_object('raw_sha256',repeat('a',64),'source_date','2025-08-01'))
      """,
        run);
    jdbc.update(
        """
      insert into public.external_api_snapshots(id,import_run_id,source_provider,source_service,source_operation,
      scope_key,request_hash,page_key,fetched_at,parser_version,payload_hash,request_metadata_redacted,
      raw_payload,payload_size_bytes,redaction_version,payload_format,initial_parse_status,parse_status,parsed_at)
      values (?,?,'한국공항공사','15002851','15002851','global',repeat('a',64),'1',now()-interval '1 hour','test',repeat('a',64),
      '{}'::jsonb,'{}'::jsonb,2,'test','JSON','parsed','parsed',now())
      """,
        snapshot,
        run);
    jdbc.update(
        """
      insert into public.tour_places(id,name,normalized_name,category,location,source_provider,source_service,import_run_id,source_snapshot_id)
      values (?,'제주국제공항','제주국제공항','airport',st_setsrid(st_makepoint(126.492778,33.511111),4326)::geography,'한국공항공사','15002851',?,?)
      """,
        place,
        run,
        snapshot);
    jdbc.update(
        """
      insert into public.approved_airport_place_bindings
      (place_id,source_id,external_id,source_record_id,dataset_id,source_date,retrieved_at,expires_at,raw_sha256,import_run_id,latitude,longitude)
      values (?,'kac.airport','CJU','제주','15002851','2025-08-01',now()-interval '1 hour',now()+interval '1 day',repeat('a',64),?,33.511111,126.492778)
      """,
        place,
        run);
    var airport = new JdbcTripAirportResolver(jdbc, place.toString());
    var resolver = new JdbcGenerationPlaceResolver(jdbc);
    assertThat(airport.findApproved()).contains(place);
    assertThat(resolver.resolve(Set.of(place), Instant.now()).factId(place))
        .isEqualTo("kac.airport:CJU");
    assertThat(
            resolver
                .resolveFactIds(Set.of("kac.airport:CJU"), Instant.now())
                .canonicalId("kac.airport:CJU"))
        .isEqualTo(place);
    jdbc.update(
        "update public.approved_airport_place_bindings set retrieved_at=now() where place_id=?",
        place);
    assertThat(airport.findApproved()).as("원본을 다시 수집하지 않고 binding 시각만 갱신하면 거부한다").isEmpty();
    jdbc.update(
        "update public.approved_airport_place_bindings set retrieved_at=now()-interval '1 hour' where place_id=?",
        place);
    jdbc.update(
        "update public.approved_airport_place_bindings set raw_sha256=repeat('b',64) where place_id=?",
        place);
    assertThat(airport.findApproved()).isEmpty();
    assertThatThrownBy(() -> resolver.resolve(Set.of(place), Instant.now()))
        .hasMessage("GENERATION_INPUT_UNAVAILABLE");
    jdbc.update(
        "update public.approved_airport_place_bindings set raw_sha256=repeat('a',64),expires_at=now()-interval '1 minute' where place_id=?",
        place);
    assertThat(airport.findApproved()).isEmpty();
  }
}
