package com.timingjeju.api.global.staypolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timingjeju.api.application.staypolicy.StayPolicyImportResult;
import com.timingjeju.api.application.staypolicy.StayPolicyImportService;
import com.timingjeju.api.application.staypolicy.StayPolicyPublicationStore;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetCatalog;
import com.timingjeju.api.application.staypolicy.StayPolicyTargetValidation;
import com.timingjeju.api.application.staypolicy.StayPolicyValidationException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class StayPolicyImportRunnerTest {

  private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");

  @TempDir Path importRoot;

  @Test
  void runner가_정상_설정에서_명령을_실행한다() throws Exception {
    AtomicReference<StayPolicyImportOptions> optionsRef = new AtomicReference<>();
    StayPolicyCsvImportCommand command = mock(StayPolicyCsvImportCommand.class);
    when(command.execute(any()))
        .thenAnswer(
            invocation -> {
              optionsRef.set(invocation.getArgument(0));
              return new StayPolicyImportResult("v2026-08-23", "hash", 1, false);
            });

    StayPolicyImportRunner runner = newStayPolicyImportRunner(command);

    assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    StayPolicyImportOptions options = optionsRef.get();
    assertThat(options).isNotNull();
    assertThat(options.version()).isEqualTo("v2026-08-23");
    assertThat(options.dryRun()).isFalse();
    assertThat(options.expectedActiveVersion()).isNull();
  }

  @Test
  void runner가_dry_run에서_DB_검증만_수행한다() throws Exception {
    AtomicReference<StayPolicyImportOptions> optionsRef = new AtomicReference<>();
    StayPolicyCsvImportCommand command = mock(StayPolicyCsvImportCommand.class);
    when(command.execute(any()))
        .thenAnswer(
            invocation -> {
              optionsRef.set(invocation.getArgument(0));
              return new StayPolicyImportResult("v2026-08-23", "hash", 1, true);
            });

    StayPolicyImportRunner runner = newStayPolicyImportRunner(command, "true", "v2026-08");

    assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    StayPolicyImportOptions options = optionsRef.get();
    assertThat(options).isNotNull();
    assertThat(options.version()).isEqualTo("v2026-08-23");
    assertThat(options.dryRun()).isTrue();
    assertThat(options.expectedActiveVersion()).isEqualTo("v2026-08");
  }

  @Test
  void runner는_expected_active_version_빈문자면_null로_처리한다() throws Exception {
    AtomicReference<StayPolicyImportOptions> optionsRef = new AtomicReference<>();
    StayPolicyCsvImportCommand command = mock(StayPolicyCsvImportCommand.class);
    when(command.execute(any()))
        .thenAnswer(
            invocation -> {
              optionsRef.set(invocation.getArgument(0));
              return new StayPolicyImportResult("v2026-08-23", "hash", 1, false);
            });

    StayPolicyImportRunner runner = newStayPolicyImportRunner(command, "false", " ");

    assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    assertThat(optionsRef.get().expectedActiveVersion()).isNull();
  }

  @Test
  void runner가_필수_설정이_누락되면_즉시_실패한다() {
    StayPolicyImportRunner runner =
        new StayPolicyImportRunner(
            new MockEnvironment()
                .withProperty("app.stay-policy.import.root", importRoot.toString())
                .withProperty("app.stay-policy.import.file", "")
                .withProperty("app.stay-policy.import.version", "v2026-08-23")
                .withProperty("app.stay-policy.import.effective-at", NOW.toString())
                .withProperty("app.stay-policy.import.dry-run", "false"),
            new StayPolicyImportService(
                new TestStore(), new TestStore(), Clock.fixed(NOW, ZoneOffset.UTC)));

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("app.stay-policy.import.file");
  }

  @Test
  void runner가_잘못된_dry_run_값에서_오류를_던진다() {
    StayPolicyImportRunner runner =
        new StayPolicyImportRunner(
            new MockEnvironment()
                .withProperty("app.stay-policy.import.root", importRoot.toString())
                .withProperty("app.stay-policy.import.file", "/tmp/policy.csv")
                .withProperty("app.stay-policy.import.version", "v2026-08-23")
                .withProperty("app.stay-policy.import.effective-at", NOW.toString())
                .withProperty("app.stay-policy.import.dry-run", "1"),
            new StayPolicyImportService(
                new TestStore(), new TestStore(), Clock.fixed(NOW, ZoneOffset.UTC)));

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(StayPolicyFileException.class)
        .hasMessageContaining("must be exactly true or false");
  }

  @Test
  void runner가_커맨드_예외를_전파한다() throws Exception {
    StayPolicyCsvImportCommand command = mock(StayPolicyCsvImportCommand.class);
    when(command.execute(any()))
        .thenThrow(
            new StayPolicyValidationException(
                java.util.List.of("policy payload validation failed")));

    StayPolicyImportRunner runner = newStayPolicyImportRunner(command, "false", "v2026-08");

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(StayPolicyValidationException.class)
        .hasMessageContaining("validation failed");
    verify(command).execute(any(StayPolicyImportOptions.class));
  }

  private StayPolicyImportRunner newStayPolicyImportRunner(StayPolicyCsvImportCommand command)
      throws Exception {
    return newStayPolicyImportRunner(command, "false", null);
  }

  private StayPolicyImportRunner newStayPolicyImportRunner(
      StayPolicyCsvImportCommand command, String dryRun, String expectedActiveVersion)
      throws Exception {
    var environment =
        new MockEnvironment()
            .withProperty("app.stay-policy.import.root", importRoot.toString())
            .withProperty("app.stay-policy.import.file", "/tmp/policy.csv")
            .withProperty("app.stay-policy.import.version", "v2026-08-23")
            .withProperty("app.stay-policy.import.effective-at", NOW.toString())
            .withProperty("app.stay-policy.import.dry-run", dryRun);
    if (expectedActiveVersion != null) {
      environment.withProperty(
          "app.stay-policy.import.expected-active-version", expectedActiveVersion);
    }
    StayPolicyImportRunner runner =
        new StayPolicyImportRunner(
            environment,
            new StayPolicyImportService(
                new TestStore(), new TestStore(), Clock.fixed(NOW, ZoneOffset.UTC)));
    injectCommand(runner, command);
    return runner;
  }

  private void injectCommand(StayPolicyImportRunner runner, StayPolicyCsvImportCommand command)
      throws Exception {
    Field commandField = StayPolicyImportRunner.class.getDeclaredField("command");
    commandField.setAccessible(true);
    commandField.set(runner, command);
  }

  private static final class TestStore
      implements StayPolicyTargetCatalog, StayPolicyPublicationStore {
    @Override
    public StayPolicyTargetValidation validateTargets(Set<String> categories, Set<UUID> placeIds) {
      return new StayPolicyTargetValidation(categories, placeIds);
    }

    @Override
    public void publish(
        com.timingjeju.api.application.staypolicy.ValidatedStayPolicyPayload payload,
        java.time.Instant importedAt) {}
  }
}
