package com.timingjeju.api.domain.transportevent.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdbcTransportEventStoreArchitectureTest {
  @Test
  void transport_store는_공통_trip_aggregate_coordinator를_사용하고_root_SQL을_소유하지_않는다() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/com/timingjeju/api/domain/transportevent/adapter/JdbcTransportEventStore.java"));

    assertThat(source).contains("TripAggregateMutationCoordinator");
    assertThat(source)
        .doesNotContain("for update")
        .doesNotContain("update public.trip_plans")
        .doesNotContain("update public.trip_schedule_versions")
        .doesNotContain("advanceRoot(");
  }
}
