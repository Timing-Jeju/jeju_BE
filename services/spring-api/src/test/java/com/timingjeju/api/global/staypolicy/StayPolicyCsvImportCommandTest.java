package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.timingjeju.api.application.staypolicy.StayPolicyImportService;
import com.timingjeju.api.application.staypolicy.StayPolicyPublicationStore;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetCatalog;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetValidation;
import com.timingjeju.api.application.staypolicy.ValidatedStayPolicyPayload;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class StayPolicyCsvImportCommandTest {

  @TempDir Path root;

  @Test
  void versioned_CSV를_유일한_service_writer에_전달하고_dryRun은_write0이다() throws Exception {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
      assumeTrue(stream instanceof SecureDirectoryStream<?>);
    }
    Instant now = Instant.parse("2026-08-23T09:00:00Z");
    RecordingStore store = new RecordingStore();
    StayPolicyImportService service =
        new StayPolicyImportService(store, store, Clock.fixed(now, ZoneOffset.UTC));
    Path file = root.resolve("policy.csv");
    Files.writeString(file, "scope,category,placeId,minutes\ncategory_default,VE,,90\n");
    StayPolicyCsvImportCommand command = new StayPolicyCsvImportCommand(service);
    StayPolicyImportOptions options =
        new StayPolicyImportOptions(root, file, "v1", now, null, false);

    var imported = command.execute(options);
    var dryRun = command.execute(new StayPolicyImportOptions(root, file, "v1", now, null, true));

    assertThat(imported.version()).isEqualTo("v1");
    assertThat(imported.payloadHash()).hasSize(64);
    assertThat(dryRun.dryRun()).isTrue();
    assertThat(store.publishCalls).isEqualTo(1);
  }

  private static final class RecordingStore
      implements StayPolicyTargetCatalog, StayPolicyPublicationStore {
    private int publishCalls;

    @Override
    public StayPolicyTargetValidation validateTargets(Set<String> categories, Set<UUID> placeIds) {
      return new StayPolicyTargetValidation(categories, placeIds);
    }

    @Override
    public void publish(ValidatedStayPolicyPayload payload, Instant importedAt) {
      publishCalls++;
    }
  }
}
