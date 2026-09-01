package com.timingjeju.api.domain.transportevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransportEventMigrationContractTest {
  private static final String MIGRATION = "20260907000000_trip_transport_event_contract.sql";

  @Test
  void migration은_legacy를_추측보정하지않고_XOR_canonical_server_writer를_강제한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("legacy transport event contract conflict")
        .contains("num_nonnulls(terminal_place_id, terminal_name) = 1")
        .contains("normalize(terminal_name, NFC)")
        .contains("char_length(transport_number) between 1 and 30")
        .contains("char_length(note) between 1 and 500")
        .contains("revoke all on public.trip_transport_events from authenticated")
        .doesNotContain("delete from public.trip_transport_events")
        .doesNotContain("Flyway");
  }

  @Test
  void compose와_Docker_upgrade는_037을_seed전에_정확히_적용한다() throws Exception {
    Path root = root();
    String target = "037_trip_transport_event_contract.sql";
    for (String compose :
        java.util.List.of("compose.yml", "compose.test.yml", "docker-compose.yml")) {
      String text = Files.readString(root.resolve(compose));
      assertThat(text.indexOf(target)).as(compose).isGreaterThanOrEqualTo(0);
      assertThat(text.indexOf(target))
          .as(compose)
          .isLessThan(text.indexOf("099_seed_fixtures.sql"));
    }
    assertThat(Files.readString(root.resolve("scripts/docker-smoke-test.sh")))
        .contains("/docker-entrypoint-initdb.d/037_trip_transport_event_contract.sql");
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) return current;
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
