package com.timingjeju.api.application.commandinput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class McpCommandLocationResolverTest {
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
  private static final CommandInputParent PARENT =
      new CommandInputParent.Compute(UUID.fromString("10900000-0000-0000-0000-000000000001"));

  @Test
  void MCP_직전마다_DB에_evaluatedAt을_전달해_usable_location을_재검증한다() {
    CommandInputSnapshotRepository repository = mock(CommandInputSnapshotRepository.class);
    CommandLocationSnapshot location =
        new CommandLocationSnapshot(
            "{\"placeId\":\"10900000-0000-0000-0000-000000000002\",\"type\":\"PLACE\"}",
            null,
            "2026-08-11.v1",
            NOW.minusSeconds(10),
            NOW.plusSeconds(1));
    when(repository.findUsableLocation(PARENT, NOW)).thenReturn(Optional.of(location));
    var resolver = new McpCommandLocationResolver(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(resolver.resolveImmediatelyBeforeMcp(PARENT)).contains(location);
    assertThat(resolver.resolveImmediatelyBeforeMcp(PARENT)).contains(location);
    verify(repository, org.mockito.Mockito.times(2)).findUsableLocation(PARENT, NOW);
  }

  @Test
  void expiry_정확경계나_redaction으로_repository가_empty면_MCP에_위치를_전달하지_않는다() {
    CommandInputSnapshotRepository repository = mock(CommandInputSnapshotRepository.class);
    when(repository.findUsableLocation(PARENT, NOW)).thenReturn(Optional.empty());
    var resolver = new McpCommandLocationResolver(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(resolver.resolveImmediatelyBeforeMcp(PARENT)).isEmpty();
  }
}
