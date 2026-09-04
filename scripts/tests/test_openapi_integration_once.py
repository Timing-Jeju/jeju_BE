from __future__ import annotations

import contextlib
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

from scripts import gradle_stage_watchdog as watchdog


ROOT = Path(__file__).resolve().parents[2]
SPRING = ROOT / "services" / "spring-api"
BUILD_GRADLE = SPRING / "build.gradle"
QUALITY_GATE = ROOT / "scripts" / "quality-gate.sh"
WINDOWS_GATE = ROOT / "scripts" / "quality-gate.ps1"
WATCHDOG = ROOT / "scripts" / "gradle_stage_watchdog.py"
ROOT_COMPLETE_PREFIX = "TIMING_JEJU_TEST_ROOT_COMPLETE"


class RecordingWindowsProcessTreeGuard:
    def __init__(self) -> None:
        self.owned: dict[tuple[int, str | None], watchdog.ProcessIdentity] = {}
        self.terminated: list[watchdog.ProcessIdentity] = []
        self.closed = False
        self.close_result = True

    def owned_with_pid(self, pid: int) -> watchdog.ProcessIdentity | None:
        matches = [item for item in self.owned.values() if item.pid == pid]
        return matches[0] if len(matches) == 1 else None

    def acquire(
        self,
        identity: watchdog.ProcessIdentity,
        parent: watchdog.ProcessIdentity | None = None,
    ) -> bool:
        incarnation = watchdog.process_incarnation(identity)
        if self.owned_with_pid(identity.pid) not in (None, identity):
            return False
        if parent is not None and (
            self.owned.get(watchdog.process_incarnation(parent)) != parent
            or identity.ppid != parent.pid
            or watchdog.creation_not_before(identity, parent) is not True
        ):
            return False
        self.owned[incarnation] = identity
        return True

    def discover(self, inventory: tuple[watchdog.ProcessIdentity, ...]) -> bool:
        conflict = any(
            self.owned_with_pid(item.pid) is not None
            and watchdog.process_incarnation(item) not in self.owned
            for item in inventory
        )
        changed = True
        while changed:
            changed = False
            for item in inventory:
                if watchdog.process_incarnation(item) in self.owned:
                    continue
                parent = self.owned_with_pid(item.ppid)
                if parent is not None and watchdog.creation_not_before(item, parent) is True:
                    self.acquire(item, parent=parent)
                    changed = True
        return conflict

    def live_owned(
        self, inventory: tuple[watchdog.ProcessIdentity, ...], *, excluding_pid: int
    ) -> list[watchdog.ProcessIdentity]:
        return [
            item
            for item in inventory
            if item.pid != excluding_pid
            and watchdog.process_incarnation(item) in self.owned
        ]

    def terminate(self, identity: watchdog.ProcessIdentity) -> bool:
        self.terminated.append(identity)
        return True

    def close(self) -> bool:
        self.closed = True
        return self.close_result


class OpenApiIntegrationOnceTest(unittest.TestCase):
    def test_gradle_graph_has_dedicated_sole_writer_and_no_full_integration_dependency(self) -> None:
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")

        self.assertIn(
            "def openApiWriterClass = "
            "'com.timingjeju.api.documentation.OpenApiDocumentationTest'",
            gradle,
        )
        self.assertRegex(
            gradle,
            r"integrationTestTask\.configure\s*\{[\s\S]*?"
            r"excludeTestsMatching openApiWriterClass[\s\S]*?\}",
        )
        default_test = self._block(gradle, "tasks.named('test', Test)")
        self.assertIn("excludeTags 'integration'", default_test)
        dedicated = self._block(gradle, "tasks.register('openApiDocsTest', Test)")
        self.assertIn("includeTestsMatching openApiWriterClass", dedicated)
        self.assertIn("outputs.file openApiOutput", dedicated)
        self.assertIn("outputs.upToDateWhen { false }", dedicated)
        self.assertIn("delete openApiOutput", dedicated)
        self.assertIn("OpenAPI JSON이 생성되지 않았거나 비어 있습니다.", dedicated)
        lifecycle = self._block(gradle, "tasks.register('openApiDocs')")
        self.assertIn("dependsOn openApiDocsTestTask", lifecycle)
        self.assertNotIn("integrationTestTask", lifecycle)
        self.assertNotIn("outputs.file openApiOutput", lifecycle)
        integration = self._block(gradle, "integrationTestTask.configure")
        self.assertNotIn("outputs.file openApiOutput", integration)
        check = self._block(gradle, "check {")
        self.assertIn("integrationTestTask", check)

    def test_writer_class_is_selected_without_new_tag_and_root_suite_marker_is_emitted(
        self,
    ) -> None:
        writer = (
            SPRING
            / "src/test/java/com/timingjeju/api/documentation/OpenApiDocumentationTest.java"
        ).read_text(encoding="utf-8")
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")

        self.assertIn('@Tag("integration")', writer)
        self.assertNotIn('@Tag("openapi-docs")', writer)
        self.assertIn("includeTestsMatching openApiWriterClass", gradle)
        self.assertIn(ROOT_COMPLETE_PREFIX, gradle)
        self.assertIn("descriptor.parent == null", gradle)
        self.assertIn("task=${path}", gradle)

    def test_unix_quality_gate_runs_full_integration_once_and_fails_closed_on_artifact(self) -> None:
        gate = QUALITY_GATE.read_text(encoding="utf-8")

        self.assertEqual(1, gate.count('run_bounded_spring_gradle "integrationTest"'))
        self.assertEqual(1, gate.count('run_bounded_spring_gradle "openApiDocs"'))
        self.assertNotIn("run_spring_gradle integrationTest", gate)
        self.assertNotIn("run_spring_gradle openApiDocs", gate)
        stale_delete = gate.index("rm -f services/spring-api/build/openapi/openapi.json")
        stale_check = gate.index('if [ -e services/spring-api/build/openapi/openapi.json ]')
        generation = gate.index('run_bounded_spring_gradle "openApiDocs"')
        non_empty = gate.index('if [ ! -s services/spring-api/build/openapi/openapi.json ]')
        readiness = gate.index("python3 scripts/validate_openapi_frontend_readiness.py")
        self.assertLess(stale_delete, stale_check)
        self.assertLess(stale_check, generation)
        self.assertLess(generation, non_empty)
        self.assertLess(non_empty, readiness)
        self.assertIn("--post-suite-timeout-seconds 120", gate)

    def test_unix_watchdog_invocation_is_one_parser_safe_logical_line(self) -> None:
        gate = QUALITY_GATE.read_text(encoding="utf-8")
        invocations = [
            line.strip()
            for line in gate.splitlines()
            if "gradle_stage_watchdog.py" in line
        ]

        self.assertEqual(1, len(invocations))
        invocation = invocations[0]
        self.assertFalse(invocation.endswith("\\"))
        self.assertIn('--stage "$STAGE_NAME"', invocation)
        self.assertIn('--expected-marker "$EXPECTED_MARKER"', invocation)
        self.assertIn('-- ./gradlew --no-daemon "$@"', invocation)

    def test_windows_quality_gate_uses_same_exact_once_and_artifact_boundary(self) -> None:
        gate = WINDOWS_GATE.read_text(encoding="utf-8")

        self.assertEqual(1, gate.count('Invoke-BoundedSpringGradle "integrationTest"'))
        self.assertEqual(1, gate.count('Invoke-BoundedSpringGradle "openApiDocs"'))
        self.assertNotIn("unitTest sliceTest integrationTest architectureTest", gate)
        self.assertIn("unitTest sliceTest architectureTest", gate)
        stale_delete = gate.index(
            'Remove-Item -LiteralPath "build/openapi/openapi.json" -Force -ErrorAction Stop'
        )
        generation = gate.index('Invoke-BoundedSpringGradle "openApiDocs"')
        non_empty = gate.index('if ((Get-Item -LiteralPath "build/openapi/openapi.json").Length -le 0)')
        readiness = gate.index("validate_openapi_frontend_readiness.py")
        self.assertLess(stale_delete, generation)
        self.assertLess(generation, non_empty)
        self.assertLess(non_empty, readiness)

    def test_windows_watchdog_invocation_is_wrapped_by_native_fail_fast(self) -> None:
        gate = WINDOWS_GATE.read_text(encoding="utf-8")
        invocations = [
            line.strip()
            for line in gate.splitlines()
            if "py -3 $watchdog" in line
        ]

        self.assertEqual(1, len(invocations))
        invocation = invocations[0]
        self.assertTrue(invocation.startswith('Invoke-Native "Spring $Stage" { py -3'))
        self.assertIn("--post-suite-timeout-seconds 120", invocation)
        self.assertIn("-- ./gradlew.bat --no-daemon @GradleArgs", invocation)

    def test_quality_gates_delete_stale_jacoco_data_before_first_test_stage(self) -> None:
        unix = QUALITY_GATE.read_text(encoding="utf-8")
        windows = WINDOWS_GATE.read_text(encoding="utf-8")

        unix_delete = 'rm -rf "$SPRING_DIR/build/jacoco"'
        unix_absence = 'if [ -e "$SPRING_DIR/build/jacoco" ]'
        self.assertEqual(1, unix.count(unix_delete))
        self.assertEqual(1, unix.count(unix_absence))
        self.assertLess(unix.index(unix_delete), unix.index(unix_absence))
        self.assertLess(unix.index(unix_absence), unix.index('run_spring_gradle unitTest'))

        windows_delete = (
            'Remove-Item -LiteralPath $jacocoDir -Recurse -Force -ErrorAction Stop'
        )
        windows_absence = 'if (Test-Path -LiteralPath $jacocoDir)'
        self.assertEqual(1, windows.count(windows_delete))
        self.assertEqual(2, windows.count(windows_absence))
        self.assertLess(windows.index(windows_delete), windows.rindex(windows_absence))
        self.assertLess(
            windows.rindex(windows_absence),
            windows.index('Invoke-Native "Spring 분류 테스트"'),
        )

    def test_coverage_aggregates_exact_current_run_execution_data_and_fails_closed(self) -> None:
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")
        expected_tasks = (
            "unitTest",
            "sliceTest",
            "integrationTest",
            "openApiDocsTest",
            "architectureTest",
            "test",
        )

        declaration_start = gradle.index("def requiredCoverageTaskNames = [")
        declaration_end = gradle.index("]", declaration_start)
        declaration = gradle[declaration_start : declaration_end + 1]
        self.assertEqual(
            set(expected_tasks),
            {name for name in expected_tasks if f"'{name}'" in declaration},
        )
        self.assertIn(
            'layout.buildDirectory.file("jacoco/${taskName}.exec")', gradle
        )
        precondition = self._block(
            gradle, "tasks.register('verifyRequiredCoverageExecutionData')"
        )
        self.assertIn("requiredCoverageExecutionData.each", precondition)
        self.assertIn("!executionFile.isFile()", precondition)
        self.assertIn("executionFile.length() == 0", precondition)
        self.assertNotIn("dependsOn requiredCoverageTestTasks", precondition)

        report = self._block(gradle, "jacocoTestReport {")
        verification = self._block(gradle, "jacocoTestCoverageVerification {")
        for coverage_task in (report, verification):
            self.assertIn("dependsOn verifyRequiredCoverageExecutionData", coverage_task)
            self.assertIn("executionData requiredCoverageExecutionData", coverage_task)
            self.assertNotIn("dependsOn test", coverage_task)

    def test_each_test_task_owns_its_jacoco_execution_data_output(self) -> None:
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")
        test_configuration = self._block(gradle, "tasks.withType(Test).configureEach")

        self.assertIn(
            'layout.buildDirectory.file("jacoco/${name}.exec")', test_configuration
        )
        self.assertIn(
            "extension.destinationFile = jacocoExecutionData.get().asFile",
            test_configuration,
        )
        self.assertIn("outputs.file(jacocoExecutionData)", test_configuration)
        self.assertIn(
            'withPropertyName("jacocoExecutionData")', test_configuration
        )

    def test_check_schedules_required_coverage_writers_without_report_reruns(self) -> None:
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")
        precondition = self._block(
            gradle, "tasks.register('verifyRequiredCoverageExecutionData')"
        )
        check = self._block(gradle, "check {")

        self.assertIn("mustRunAfter requiredCoverageTestTasks", precondition)
        self.assertIn("dependsOn requiredCoverageTestTasks", check)
        self.assertNotIn("tasks.withType(Test)", precondition)

    def test_watchdog_defaults_and_diagnostics_are_bounded_and_secret_safe(self) -> None:
        self.assertTrue(WATCHDOG.is_file(), "bounded Gradle watchdog가 없습니다.")
        source = WATCHDOG.read_text(encoding="utf-8")

        self.assertIn("DEFAULT_POST_SUITE_TIMEOUT_SECONDS = 120", source)
        self.assertIn("Thread.print", source)
        self.assertIn("DOCKER_RESOURCE_COMMANDS", source)
        self.assertIn("new-docker-resource-residue", source)
        self.assertIn("pid", source)
        self.assertIn("ppid", source)
        self.assertIn("actor", source)
        self.assertIn("disk", source)
        self.assertNotIn("docker rm", source)
        self.assertNotIn("docker volume rm", source)
        self.assertNotIn("docker system prune", source)
        self.assertNotIn("os.environ", source)

    def test_watchdog_snapshots_all_docker_resource_identities_without_mutation(self) -> None:
        outputs = (
            subprocess.CompletedProcess(
                [],
                0,
                "container-id\tprotected-api\trunning\tUp 2 minutes (healthy)\n",
                "",
            ),
            subprocess.CompletedProcess(
                [], 0, "protected-volume\tprotected-volume\tvolume\n", ""
            ),
            subprocess.CompletedProcess(
                [], 0, "network-id\tprotected-network\tbridge\n", ""
            ),
            subprocess.CompletedProcess(
                [], 0, "sha256:image\trepository:tag\t2 days ago\n", ""
            ),
        )
        with patch.object(watchdog, "run_quiet", side_effect=outputs) as run:
            inspection = watchdog.inspect_docker_resources()

        self.assertTrue(inspection.trusted)
        self.assertEqual(
            {"container", "volume", "network", "image"},
            {resource.kind for resource in inspection.resources},
        )
        self.assertEqual(4, run.call_count)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(
            ["container", "volume", "network", "image"],
            [command[1] for command in commands],
        )
        self.assertTrue(all("rm" not in command and "prune" not in command for command in commands))
        self.assertIn("--no-trunc", commands[0])
        self.assertIn("--no-trunc", commands[2])
        for resource in inspection.resources:
            self.assertLessEqual(len(resource.id), watchdog.MAX_RESOURCE_FIELD_LENGTH)
            self.assertLessEqual(len(resource.name), watchdog.MAX_RESOURCE_FIELD_LENGTH)
            self.assertLessEqual(len(resource.status), watchdog.MAX_RESOURCE_FIELD_LENGTH)
            self.assertLessEqual(
                set(dict(resource.labels)), watchdog.ALLOWLISTED_DOCKER_LABELS
            )

    def test_diagnostic_resource_record_is_bounded_and_label_allowlisted(self) -> None:
        resource = watchdog.DockerResourceIdentity(
            "container",
            "i" * 300,
            "n" * 300,
            "running\nwith-control" + ("s" * 300),
            (
                ("org.testcontainers", "true"),
                ("secret.token", "must-not-appear"),
            ),
        )

        record = watchdog.docker_resource_record(resource)

        self.assertEqual(
            {"kind", "id", "name", "status", "labels", "state", "health"},
            set(record),
        )
        for field in ("id", "name", "status"):
            self.assertLessEqual(len(record[field]), watchdog.MAX_RESOURCE_FIELD_LENGTH)
        self.assertEqual({"org.testcontainers": "true"}, record["labels"])
        self.assertNotIn("must-not-appear", str(record))

    def test_image_tag_and_long_raw_name_are_distinct_snapshot_identities(self) -> None:
        prefix = "repository/" + ("n" * 170)
        old = watchdog.DockerResourceIdentity(
            "image", "sha256:same", prefix + ":old", "created", ()
        )
        new = watchdog.DockerResourceIdentity(
            "image", "sha256:same", prefix + ":new", "created", ()
        )
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=(old,))
        current = watchdog.DockerResourceInspection(trusted=True, resources=(old, new))

        with patch.object(watchdog, "inspect_docker_resources", return_value=current):
            observation = watchdog.observe_docker_resources(baseline, grace_seconds=0)

        self.assertEqual((new,), observation.new)
        self.assertEqual((), observation.removed)
        self.assertGreater(len(new.name), watchdog.MAX_RESOURCE_FIELD_LENGTH)

    def test_snapshot_rejects_control_characters_in_raw_identity(self) -> None:
        outputs = (
            subprocess.CompletedProcess([], 0, "container-id\tbad\x01name\trunning\n", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
        )
        with patch.object(watchdog, "run_quiet", side_effect=outputs):
            inspection = watchdog.inspect_docker_resources()

        self.assertFalse(inspection.trusted)
        self.assertEqual((), inspection.resources)

    def test_container_snapshot_uses_stable_state_and_health_not_relative_duration(self) -> None:
        outputs = (
            subprocess.CompletedProcess(
                [], 0, "container-id\tapi\trunning\tUp 2 seconds (healthy)\n", ""
            ),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
        )
        with patch.object(watchdog, "run_quiet", side_effect=outputs) as run:
            inspection = watchdog.inspect_docker_resources()

        container = inspection.resources[0]
        self.assertIn("{{.State}}", run.call_args_list[0].args[0][-1])
        self.assertEqual("running", container.state)
        self.assertEqual("healthy", container.health)
        self.assertEqual("Up 2 seconds (healthy)", container.status)

        later = watchdog.DockerResourceIdentity(
            "container",
            container.id,
            container.name,
            "Up 2 hours (healthy)",
            (),
            state="running",
            health="healthy",
        )
        image_before = watchdog.DockerResourceIdentity(
            "image", "sha256:image", "api:latest", "2 seconds ago", ()
        )
        image_after = watchdog.DockerResourceIdentity(
            "image", "sha256:image", "api:latest", "2 hours ago", ()
        )
        baseline = watchdog.DockerResourceInspection(
            trusted=True, resources=(container, image_before)
        )
        current = watchdog.DockerResourceInspection(
            trusted=True, resources=(later, image_after)
        )
        with patch.object(watchdog, "inspect_docker_resources", return_value=current):
            observation = watchdog.observe_docker_resources(baseline, grace_seconds=0)

        self.assertEqual((), observation.state_changed)

    def test_container_state_or_health_transition_is_fail_closed_with_before_after(self) -> None:
        baseline_resource = watchdog.DockerResourceIdentity(
            "container",
            "container-id",
            "api",
            "Up 2 minutes (healthy)",
            (),
            state="running",
            health="healthy",
        )
        transitions = (
            ("exited", "none"),
            ("paused", "none"),
            ("restarting", "none"),
            ("dead", "none"),
            ("running", "unhealthy"),
        )
        for state, health in transitions:
            with self.subTest(state=state, health=health):
                current_resource = watchdog.DockerResourceIdentity(
                    "container",
                    "container-id",
                    "api",
                    "relative text must not define identity",
                    (),
                    state=state,
                    health=health,
                )
                baseline = watchdog.DockerResourceInspection(
                    trusted=True, resources=(baseline_resource,)
                )
                current = watchdog.DockerResourceInspection(
                    trusted=True, resources=(current_resource,)
                )
                with patch.object(
                    watchdog, "inspect_docker_resources", return_value=current
                ):
                    observation = watchdog.observe_docker_resources(
                        baseline, grace_seconds=0
                    )

                self.assertEqual(1, len(observation.state_changed))
                self.assertEqual(baseline_resource, observation.state_changed[0].before)
                self.assertEqual(current_resource, observation.state_changed[0].after)

                process = Mock(pid=1234)
                process.poll.return_value = 0
                with (
                    patch.object(
                        watchdog,
                        "observe_docker_resources",
                        return_value=observation,
                    ),
                    patch.object(
                        watchdog,
                        "collect_diagnostics",
                        return_value=Path("diagnostic"),
                    ) as collect,
                ):
                    result = watchdog.finalize_terminal(
                        args=SimpleNamespace(stage="integrationTest"),
                        process=process,
                        original_reason=None,
                        requested_exit_code=0,
                        root_complete=True,
                        baseline=baseline,
                    )

                self.assertEqual(watchdog.RESIDUE_EXIT_CODE, result)
                self.assertEqual(
                    "docker-container-state-changed",
                    collect.call_args.kwargs["outcome"],
                )

    def test_container_health_category_is_closed(self) -> None:
        self.assertEqual("healthy", watchdog.container_health("Up 1 second (healthy)"))
        self.assertEqual("unhealthy", watchdog.container_health("Up 1 second (unhealthy)"))
        self.assertEqual(
            "starting", watchdog.container_health("Up 1 second (health: starting)")
        )
        self.assertEqual("none", watchdog.container_health("Exited (0) 1 second ago"))

    def test_manifest_contains_bounded_container_state_before_after(self) -> None:
        before = watchdog.DockerResourceIdentity(
            "container", "id", "api", "s" * 300, (), state="running", health="healthy"
        )
        after = watchdog.DockerResourceIdentity(
            "container", "id", "api", "s" * 300, (), state="running", health="unhealthy"
        )
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=(before,))
        observation = watchdog.DockerResourceObservation(
            trusted=True,
            current=(after,),
            new=(),
            removed=(),
            state_changed=(watchdog.DockerResourceStateChange(before, after),),
        )
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "diagnostic"
            destination.mkdir()
            watchdog.collect_diagnostics(
                args=SimpleNamespace(stage="integrationTest"),
                process=Mock(pid=1234),
                original_reason=None,
                outcome="docker-container-state-changed",
                root_complete=True,
                baseline=baseline,
                observation=observation,
                runtime=watchdog.RuntimeDiagnostics(destination, (), ()),
            )
            changed = json.loads(
                (destination / "manifest.json").read_text(encoding="utf-8")
            )["dockerResources"]["stateChanged"][0]

        self.assertEqual("running", changed["before"]["state"])
        self.assertEqual("healthy", changed["before"]["health"])
        self.assertEqual("unhealthy", changed["after"]["health"])
        self.assertLessEqual(
            len(changed["after"]["status"]), watchdog.MAX_RESOURCE_FIELD_LENGTH
        )

    def test_manifest_contains_bounded_new_and_removed_resource_records(self) -> None:
        baseline_resource = watchdog.DockerResourceIdentity(
            "volume", "protected", "protected-volume", "volume", ()
        )
        new_resource = watchdog.DockerResourceIdentity(
            "image", "new", "temporary:tag", "created", ()
        )
        baseline = watchdog.DockerResourceInspection(
            trusted=True, resources=(baseline_resource,)
        )
        observation = watchdog.DockerResourceObservation(
            trusted=True,
            current=(new_resource,),
            new=(new_resource,),
            removed=(baseline_resource,),
        )
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "diagnostic"
            destination.mkdir()
            runtime = watchdog.RuntimeDiagnostics(destination, (), ())
            watchdog.collect_diagnostics(
                args=SimpleNamespace(stage="integrationTest"),
                process=Mock(pid=1234),
                original_reason=None,
                outcome="removed-baseline-docker-resource",
                root_complete=True,
                baseline=baseline,
                observation=observation,
                runtime=runtime,
            )

            resources = json.loads(
                (destination / "manifest.json").read_text(encoding="utf-8")
            )["dockerResources"]

        self.assertEqual("temporary:tag", resources["new"][0]["name"])
        self.assertEqual("protected-volume", resources["removed"][0]["name"])

    def test_fresh_runner_dependency_image_cache_is_not_runtime_residue(self) -> None:
        cached_image = watchdog.DockerResourceIdentity(
            "image",
            "sha256:dependency",
            "postgis/postgis:16-3.4",
            "created",
            (),
        )
        dangling_image = watchdog.DockerResourceIdentity(
            "image", "sha256:dangling", "<none>:<none>", "created", ()
        )
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())

        with patch.object(
            watchdog,
            "inspect_docker_resources",
            return_value=watchdog.DockerResourceInspection(
                trusted=True, resources=(cached_image,)
            ),
        ):
            cached = watchdog.observe_docker_resources(baseline, grace_seconds=0)
        with patch.object(
            watchdog,
            "inspect_docker_resources",
            return_value=watchdog.DockerResourceInspection(
                trusted=True, resources=(cached_image, dangling_image)
            ),
        ):
            residue = watchdog.observe_docker_resources(baseline, grace_seconds=0)

        self.assertEqual((), cached.new)
        self.assertEqual((cached_image,), cached.cached)
        self.assertEqual((dangling_image,), residue.new)
        self.assertEqual((cached_image,), residue.cached)

    def test_every_terminal_path_fails_closed_on_each_new_docker_resource_kind(self) -> None:
        protected = tuple(
            watchdog.DockerResourceIdentity(kind, f"protected-{kind}", f"live-{kind}", "ready", ())
            for kind in ("container", "volume", "network", "image")
        )
        new = tuple(
            watchdog.DockerResourceIdentity(kind, f"new-{kind}", f"temp-{kind}", "created", ())
            for kind in ("container", "volume", "network", "image")
        )
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=protected)
        residue = watchdog.DockerResourceObservation(
            trusted=True, current=protected + new, new=new, removed=()
        )
        cases = (
            (None, 0, True),
            ("command-failed", 1, True),
            ("stage-timeout", 124, False),
            ("interrupted", 130, False),
        )

        for reason, requested_exit, already_stopped in cases:
            process = Mock(pid=1234)
            process.poll.return_value = 0 if already_stopped else None
            runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
            events: list[str] = []
            with (
                self.subTest(reason=reason),
                patch.object(
                    watchdog,
                    "capture_runtime_diagnostics",
                    side_effect=lambda *_: events.append("capture") or runtime,
                ),
                patch.object(
                    watchdog,
                    "observe_docker_resources",
                    side_effect=lambda *_: events.append("observe") or residue,
                ),
                patch.object(
                    watchdog, "collect_diagnostics", return_value=Path("diagnostic")
                ) as collect,
                patch.object(
                    watchdog,
                    "terminate",
                    side_effect=lambda *_: events.append("terminate"),
                ),
            ):
                result = watchdog.finalize_terminal(
                    args=SimpleNamespace(stage="integrationTest"),
                    process=process,
                    original_reason=reason,
                    requested_exit_code=requested_exit,
                    root_complete=reason != "stage-timeout",
                    baseline=baseline,
                )

            self.assertEqual(watchdog.RESIDUE_EXIT_CODE, result)
            self.assertEqual(
                "new-docker-resource-residue", collect.call_args.kwargs["outcome"]
            )
            self.assertEqual(protected, collect.call_args.kwargs["baseline"].resources)
            self.assertEqual(new, collect.call_args.kwargs["observation"].new)
            self.assertEqual("observe", events[-1])

    def test_every_terminal_path_fails_closed_when_protected_baseline_is_removed(self) -> None:
        protected = tuple(
            watchdog.DockerResourceIdentity(kind, f"protected-{kind}", f"live-{kind}", "ready", ())
            for kind in ("container", "volume", "network", "image")
        )
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=protected)
        missing = watchdog.DockerResourceObservation(
            trusted=True, current=(), new=(), removed=protected
        )
        cases = (
            (None, 0, True),
            ("command-failed", 1, True),
            ("stage-timeout", 124, False),
            ("interrupted", 130, False),
        )
        for reason, requested_exit, already_stopped in cases:
            process = Mock(pid=1234)
            process.poll.return_value = 0 if already_stopped else None
            runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
            with (
                self.subTest(reason=reason),
                patch.object(
                    watchdog, "capture_runtime_diagnostics", return_value=runtime
                ),
                patch.object(
                    watchdog, "observe_docker_resources", return_value=missing
                ),
                patch.object(
                    watchdog, "collect_diagnostics", return_value=Path("diagnostic")
                ) as collect,
                patch.object(watchdog, "terminate"),
            ):
                result = watchdog.finalize_terminal(
                    args=SimpleNamespace(stage="integrationTest"),
                    process=process,
                    original_reason=reason,
                    requested_exit_code=requested_exit,
                    root_complete=reason != "stage-timeout",
                    baseline=baseline,
                )

            self.assertEqual(watchdog.RESIDUE_EXIT_CODE, result)
            self.assertEqual(
                "removed-baseline-docker-resource",
                collect.call_args.kwargs["outcome"],
            )
            self.assertEqual(protected, collect.call_args.kwargs["observation"].removed)

    def test_critical_guards_reject_weakened_mutations(self) -> None:
        gradle = BUILD_GRADLE.read_text(encoding="utf-8")
        unix_gate = QUALITY_GATE.read_text(encoding="utf-8")
        watchdog = WATCHDOG.read_text(encoding="utf-8")

        mutations = (
            (
                "sole-writer-filter",
                gradle.replace("excludeTestsMatching openApiWriterClass", "", 1),
                "excludeTestsMatching openApiWriterClass",
            ),
            (
                "sole-writer-class-filter",
                gradle.replace("includeTestsMatching openApiWriterClass", "", 1),
                "includeTestsMatching openApiWriterClass",
            ),
            (
                "exact-once",
                unix_gate.replace(
                    'run_bounded_spring_gradle "integrationTest"',
                    'run_spring_gradle "integrationTest"',
                    1,
                ),
                'run_bounded_spring_gradle "integrationTest"',
            ),
            (
                "zero-byte-check",
                unix_gate.replace(
                    "if [ ! -s services/spring-api/build/openapi/openapi.json ]", "", 1
                ),
                "if [ ! -s services/spring-api/build/openapi/openapi.json ]",
            ),
            *(
                (
                    f"docker-{kind}-snapshot",
                    watchdog.replace(f'    "{kind}": [', '    "removed": [', 1),
                    f'    "{kind}": [',
                )
                for kind in ("container", "volume", "network", "image")
            ),
        )
        for name, mutation, invariant in mutations:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.assertIn(invariant, mutation)

    def test_fake_completed_root_suite_with_live_worker_fails_with_diagnostic(self) -> None:
        self.assertTrue(WATCHDOG.is_file(), "bounded Gradle watchdog가 없습니다.")
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            child = (
                "import time; "
                f"print('{ROOT_COMPLETE_PREFIX} task=:integrationTest', flush=True); "
                "time.sleep(30)"
            )
            started = time.monotonic()
            result = subprocess.run(
                [
                    sys.executable,
                    str(WATCHDOG),
                    "--stage",
                    "integrationTest",
                    "--timeout-seconds",
                    "5",
                    "--post-suite-timeout-seconds",
                    "0.2",
                    "--expected-marker",
                    f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                    "--diagnostics-dir",
                    str(diagnostics),
                    "--",
                    sys.executable,
                    "-c",
                    child,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
                timeout=8,
            )
            elapsed = time.monotonic() - started

            self.assertEqual(124, result.returncode, result.stdout + result.stderr)
            self.assertLess(elapsed, 5)
            manifests = list(diagnostics.glob("*/manifest.json"))
            self.assertEqual(1, len(manifests))
            manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
            self.assertEqual("integrationTest", manifest["stage"])
            self.assertEqual("post-suite-timeout", manifest["reason"])
            self.assertTrue(manifest["rootSuiteComplete"])
            self.assertEqual("test-worker", manifest["actor"])
            self.assertIn("diagnostic", result.stderr.lower())

    def test_fake_root_suite_that_exits_completes_without_diagnostic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            child = f"print('{ROOT_COMPLETE_PREFIX} task=:openApiDocsTest', flush=True)"
            result = subprocess.run(
                [
                    sys.executable,
                    str(WATCHDOG),
                    "--stage",
                    "openApiDocs",
                    "--timeout-seconds",
                    "5",
                    "--expected-marker",
                    f"{ROOT_COMPLETE_PREFIX} task=:openApiDocsTest",
                    "--diagnostics-dir",
                    str(diagnostics),
                    "--",
                    sys.executable,
                    "-c",
                    child,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
                timeout=8,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(diagnostics.exists())

    def test_root_suite_marker_requires_an_exact_output_line(self) -> None:
        for emitted in (
            f"prefix-{ROOT_COMPLETE_PREFIX} task=:integrationTest",
            f"{ROOT_COMPLETE_PREFIX} task=:integrationTest-suffix",
        ):
            with self.subTest(emitted=emitted), tempfile.TemporaryDirectory() as temporary:
                diagnostics = Path(temporary) / "diagnostics"
                result = subprocess.run(
                    [
                        sys.executable,
                        str(WATCHDOG),
                        "--stage",
                        "integrationTest",
                        "--timeout-seconds",
                        "5",
                        "--expected-marker",
                        f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                        "--diagnostics-dir",
                        str(diagnostics),
                        "--",
                        sys.executable,
                        "-c",
                        f"print({emitted!r}, flush=True)",
                    ],
                    cwd=ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                    env=self._watchdog_environment(Path(temporary)),
                    timeout=8,
                )

                self.assertEqual(125, result.returncode, result.stdout + result.stderr)
                manifest = json.loads(
                    next(diagnostics.glob("*/manifest.json")).read_text(encoding="utf-8")
                )
                self.assertEqual("missing-root-suite-marker", manifest["reason"])
                self.assertFalse(manifest["rootSuiteComplete"])

    @unittest.skipUnless(sys.platform.startswith("linux"), "GitHub Actions Linux contract")
    def test_successful_root_allows_observed_collector_to_drain_without_signal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            diagnostics = temporary_path / "diagnostics"
            completed = temporary_path / "collector-completed"
            environment = self._watchdog_environment(temporary_path)
            descendant = (
                "import pathlib,time; "
                "time.sleep(0.25); "
                f"pathlib.Path({str(completed)!r}).write_text('natural', encoding='utf-8')"
            )
            root = (
                "import subprocess,sys,time; "
                "child=subprocess.Popen([sys.executable,'-c',"
                + repr(descendant)
                + "], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
                "print(child.pid, flush=True); "
                f"print('{ROOT_COMPLETE_PREFIX} task=:integrationTest', flush=True); "
                "time.sleep(0.1)"
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(WATCHDOG),
                    "--stage",
                    "integrationTest",
                    "--timeout-seconds",
                    "5",
                    "--post-suite-timeout-seconds",
                    "1",
                    "--expected-marker",
                    f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                    "--diagnostics-dir",
                    str(diagnostics),
                    "--",
                    sys.executable,
                    "-c",
                    root,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
                timeout=8,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("natural", completed.read_text(encoding="utf-8"))
            self.assertFalse(diagnostics.exists())

    def test_completed_posix_group_waits_for_owned_collector_without_signaling(self) -> None:
        root = watchdog.PosixProcessIdentity(100, 1, 100, "root-start")
        collector = watchdog.PosixProcessIdentity(200, 100, 100, "collector-start")
        guard = Mock()
        guard.root = root
        guard.revalidate.side_effect = ((collector,), ())
        process = Mock(pid=100)
        process.poll.return_value = 0

        with (
            patch.object(watchdog.time, "sleep"),
            patch.object(
                watchdog.time, "monotonic", side_effect=(0.0, 0.1, 0.2)
            ),
            patch.object(watchdog.os, "killpg") as killpg,
        ):
            drained = watchdog.wait_for_tracked_posix_process_group_drain(
                process, guard, timeout_seconds=1.0
            )

        self.assertTrue(drained)
        self.assertEqual(2, guard.revalidate.call_count)
        killpg.assert_not_called()

    def test_completed_posix_group_drain_is_bounded_and_identity_fail_closed(self) -> None:
        root = watchdog.PosixProcessIdentity(100, 1, 100, "root-start")
        collector = watchdog.PosixProcessIdentity(200, 100, 100, "collector-start")
        process = Mock(pid=100)
        process.poll.return_value = 0

        for members in ((collector,), None):
            with self.subTest(members=members):
                guard = Mock()
                guard.root = root
                guard.revalidate.return_value = members
                with (
                    patch.object(watchdog.time, "sleep") as sleep,
                    patch.object(watchdog.time, "monotonic", side_effect=(0.0, 0.0)),
                    patch.object(watchdog.os, "killpg") as killpg,
                ):
                    drained = watchdog.wait_for_tracked_posix_process_group_drain(
                        process, guard, timeout_seconds=0.0
                    )

                self.assertFalse(drained)
                sleep.assert_not_called()
                killpg.assert_not_called()

    @unittest.skipUnless(sys.platform != "win32", "POSIX process-group contract")
    def test_successful_root_with_unobserved_child_fails_closed_without_signaling_group(
        self,
    ) -> None:
        descendant_pid: int | None = None
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            descendant = (
                "import signal,time; "
                "signal.signal(signal.SIGINT, signal.SIG_IGN); "
                "time.sleep(30)"
            )
            root = (
                "import subprocess,sys; "
                "child=subprocess.Popen([sys.executable,'-c',"
                + repr(descendant)
                + "], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
                "print(child.pid, flush=True); "
                f"print('{ROOT_COMPLETE_PREFIX} task=:integrationTest', flush=True)"
            )
            try:
                result = subprocess.run(
                    [
                        sys.executable,
                        str(WATCHDOG),
                        "--stage",
                        "integrationTest",
                        "--timeout-seconds",
                        "5",
                        "--expected-marker",
                        f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                        "--diagnostics-dir",
                        str(diagnostics),
                        "--",
                        sys.executable,
                        "-c",
                        root,
                    ],
                    cwd=ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                    env=environment,
                    timeout=8,
                )
                descendant_pid = int(result.stdout.splitlines()[0])
                self.assertEqual(129, result.returncode, result.stdout + result.stderr)
                os.kill(descendant_pid, 0)
            finally:
                if descendant_pid is not None:
                    try:
                        os.kill(descendant_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    @unittest.skipUnless(sys.platform != "win32", "POSIX process-group contract")
    def test_root_exit_with_unobserved_stdout_descendant_fails_closed_without_signaling_group(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            descendant = (
                "import signal,time; "
                "signal.signal(signal.SIGINT, signal.SIG_IGN); "
                "time.sleep(30)"
            )
            root = (
                "import subprocess,sys; "
                "child=subprocess.Popen([sys.executable,'-c',"
                + repr(descendant)
                + "]); "
                "print(child.pid, flush=True)"
            )
            started = time.monotonic()
            descendant_pid: int | None = None
            try:
                result = subprocess.run(
                    [
                        sys.executable,
                        str(WATCHDOG),
                        "--stage",
                        "integrationTest",
                        "--timeout-seconds",
                        "0.2",
                        "--post-suite-timeout-seconds",
                        "0.1",
                        "--expected-marker",
                        f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                        "--diagnostics-dir",
                        str(diagnostics),
                        "--",
                        sys.executable,
                        "-c",
                        root,
                    ],
                    cwd=ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                    env=environment,
                    timeout=12,
                )
                elapsed = time.monotonic() - started

                self.assertEqual(129, result.returncode, result.stdout + result.stderr)
                self.assertLess(elapsed, 8)
                descendant_pid = int(result.stdout.splitlines()[0])
                os.kill(descendant_pid, 0)
                manifests = list(diagnostics.glob("*/manifest.json"))
                self.assertEqual(1, len(manifests))
                manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
                self.assertEqual("stage-timeout", manifest["reason"])
                self.assertFalse(manifest["rootSuiteComplete"])
            finally:
                if descendant_pid is not None:
                    try:
                        os.kill(descendant_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    @unittest.skipUnless(sys.platform != "win32", "POSIX process-group contract")
    def test_sigint_root_exit_does_not_hide_owned_descendant_that_ignores_sigint(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            descendant = (
                "import signal,time; "
                "signal.signal(signal.SIGINT, signal.SIG_IGN); "
                "print('child-ready', flush=True); "
                "time.sleep(30)"
            )
            root = (
                "import subprocess,sys,time; "
                "child=subprocess.Popen([sys.executable,'-c',"
                + repr(descendant)
                + "], stdout=subprocess.PIPE, text=True); "
                "assert child.stdout.readline().strip() == 'child-ready'; "
                "print(child.pid, flush=True); "
                "time.sleep(30)"
            )
            result = subprocess.run(
                [
                    sys.executable,
                    str(WATCHDOG),
                    "--stage",
                    "integrationTest",
                    "--timeout-seconds",
                    "1",
                    "--post-suite-timeout-seconds",
                    "0.1",
                    "--expected-marker",
                    f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                    "--diagnostics-dir",
                    str(diagnostics),
                    "--",
                    sys.executable,
                    "-c",
                    root,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
                timeout=12,
            )

            self.assertEqual(124, result.returncode, result.stdout + result.stderr)
            descendant_pid = int(result.stdout.splitlines()[0])
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try:
                    os.kill(descendant_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            else:
                self.fail(f"SIGINT-ignoring descendant survived pid={descendant_pid}")
            manifest = json.loads(
                next(diagnostics.glob("*/manifest.json")).read_text(encoding="utf-8")
            )
            self.assertEqual("stage-timeout", manifest["reason"])

    def test_fake_stage_without_root_marker_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = Path(temporary) / "diagnostics"
            environment = self._watchdog_environment(Path(temporary))
            result = subprocess.run(
                [
                    sys.executable,
                    str(WATCHDOG),
                    "--stage",
                    "integrationTest",
                    "--timeout-seconds",
                    "5",
                    "--expected-marker",
                    f"{ROOT_COMPLETE_PREFIX} task=:integrationTest",
                    "--diagnostics-dir",
                    str(diagnostics),
                    "--",
                    sys.executable,
                    "-c",
                    "print('completed without marker')",
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
                env=environment,
                timeout=8,
            )

            self.assertEqual(125, result.returncode, result.stdout + result.stderr)
            manifests = list(diagnostics.glob("*/manifest.json"))
            self.assertEqual(1, len(manifests))
            manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
            self.assertEqual("missing-root-suite-marker", manifest["reason"])
            self.assertFalse(manifest["rootSuiteComplete"])

    def test_all_abnormal_terminal_paths_surface_new_container_residue(self) -> None:
        args = SimpleNamespace(stage="integrationTest")
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())
        new_container = watchdog.DockerResourceIdentity(
            "container", "new", "tc", "running", ()
        )
        residue = watchdog.DockerResourceObservation(
            trusted=True,
            current=(new_container,),
            new=(new_container,),
            removed=(),
        )
        cases = (
            ("stage-timeout", 124, False),
            ("interrupted", 130, False),
            ("missing-root-suite-marker", 125, True),
        )
        for reason, requested_exit, already_stopped in cases:
            process = Mock(pid=1234)
            process.poll.return_value = 0 if already_stopped else None
            events: list[str] = []
            runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
            with (
                self.subTest(reason=reason),
                patch.object(
                    watchdog,
                    "capture_runtime_diagnostics",
                    side_effect=lambda *_: events.append("capture") or runtime,
                ) as capture,
                patch.object(
                    watchdog,
                    "observe_docker_resources",
                    side_effect=lambda *_: events.append("observe") or residue,
                ) as observe,
                patch.object(
                    watchdog, "collect_diagnostics", return_value=Path("diagnostic")
                ) as collect,
                patch.object(
                    watchdog,
                    "terminate",
                    side_effect=lambda *_: events.append("terminate"),
                ) as terminate,
            ):
                result = watchdog.finalize_terminal(
                    args=args,
                    process=process,
                    original_reason=reason,
                    requested_exit_code=requested_exit,
                    root_complete=reason != "stage-timeout",
                    baseline=baseline,
                )

                self.assertEqual(watchdog.RESIDUE_EXIT_CODE, result)
                observe.assert_called_once_with(baseline)
                collect.assert_called_once()
                self.assertEqual(reason, collect.call_args.kwargs["original_reason"])
                self.assertEqual(
                    "new-docker-resource-residue", collect.call_args.kwargs["outcome"]
                )
                capture.assert_called_once_with(args, process)
                terminate.assert_called_once_with(process)
                self.assertEqual(["capture", "terminate", "observe"], events)

    def test_untrusted_container_inspection_fails_closed_with_original_reason(
        self,
    ) -> None:
        args = SimpleNamespace(stage="integrationTest")
        process = Mock(pid=1234)
        process.poll.return_value = 1
        baseline = watchdog.DockerResourceInspection(trusted=False, resources=())
        observation = watchdog.DockerResourceObservation(
            trusted=False, current=(), new=(), removed=()
        )
        runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
        with (
            patch.object(
                watchdog, "capture_runtime_diagnostics", return_value=runtime
            ),
            patch.object(watchdog, "terminate", return_value=True),
            patch.object(watchdog, "observe_docker_resources", return_value=observation),
            patch.object(
                watchdog, "collect_diagnostics", return_value=Path("diagnostic")
            ) as collect,
        ):
            result = watchdog.finalize_terminal(
                args=args,
                process=process,
                original_reason="command-failed",
                requested_exit_code=1,
                root_complete=False,
                baseline=baseline,
            )

        self.assertEqual(watchdog.INSPECTION_EXIT_CODE, result)
        self.assertEqual("command-failed", collect.call_args.kwargs["original_reason"])
        self.assertEqual(
            "docker-resource-inspection-untrusted", collect.call_args.kwargs["outcome"]
        )

    def test_capture_failure_still_terminates_and_observes_every_live_failure(self) -> None:
        args = SimpleNamespace(stage="integrationTest")
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())
        clean = watchdog.DockerResourceObservation(
            trusted=True, current=(), new=(), removed=()
        )
        for reason in ("stage-timeout", "post-suite-timeout", "interrupted"):
            process = Mock(pid=1234)
            process.poll.return_value = None
            events: list[str] = []
            stderr = io.StringIO()

            def fail_capture(*_args: object) -> None:
                events.append("capture")
                raise OSError("secret path")

            with (
                self.subTest(reason=reason),
                patch.object(
                    watchdog,
                    "capture_runtime_diagnostics",
                    side_effect=fail_capture,
                ),
                patch.object(
                    watchdog,
                    "terminate",
                    side_effect=lambda *_: events.append("terminate"),
                ) as terminate,
                patch.object(
                    watchdog,
                    "observe_docker_resources",
                    side_effect=lambda *_: events.append("observe") or clean,
                ) as observe,
                patch.object(watchdog, "collect_diagnostics") as collect,
                contextlib.redirect_stderr(stderr),
            ):
                result = watchdog.finalize_terminal(
                    args=args,
                    process=process,
                    original_reason=reason,
                    requested_exit_code=124,
                    root_complete=True,
                    baseline=baseline,
                )

            self.assertEqual(watchdog.DIAGNOSTIC_EXIT_CODE, result)
            self.assertEqual(["capture", "terminate", "observe"], events)
            terminate.assert_called_once_with(process)
            observe.assert_called_once_with(baseline)
            collect.assert_not_called()
            self.assertIn("OSError", stderr.getvalue())
            self.assertNotIn("secret path", stderr.getvalue())

    def test_manifest_failure_returns_bounded_diagnostic_failure_after_termination(self) -> None:
        args = SimpleNamespace(stage="integrationTest")
        process = Mock(pid=1234)
        process.poll.return_value = None
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())
        clean = watchdog.DockerResourceObservation(
            trusted=True, current=(), new=(), removed=()
        )
        runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
        events: list[str] = []
        stderr = io.StringIO()

        def fail_manifest(**_kwargs: object) -> None:
            events.append("manifest")
            self.assertEqual(0, process.poll())
            raise PermissionError("secret path")

        def stop_process(*_args: object) -> None:
            events.append("terminate")
            process.poll.return_value = 0

        with (
            patch.object(
                watchdog, "capture_runtime_diagnostics", return_value=runtime
            ),
            patch.object(
                watchdog,
                "terminate",
                side_effect=stop_process,
            ) as terminate,
            patch.object(
                watchdog,
                "observe_docker_resources",
                side_effect=lambda *_: events.append("observe") or clean,
            ),
            patch.object(
                watchdog,
                "collect_diagnostics",
                side_effect=fail_manifest,
            ),
            contextlib.redirect_stderr(stderr),
        ):
            result = watchdog.finalize_terminal(
                args=args,
                process=process,
                original_reason="post-suite-timeout",
                requested_exit_code=124,
                root_complete=True,
                baseline=baseline,
            )

        self.assertEqual(watchdog.DIAGNOSTIC_EXIT_CODE, result)
        self.assertEqual(["terminate", "observe", "manifest"], events)
        terminate.assert_called_once_with(process)
        self.assertIn("PermissionError", stderr.getvalue())
        self.assertNotIn("secret path", stderr.getvalue())

    def test_windows_fallback_kills_root_tree_then_signal_resistant_descendants(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", "2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", "2026-09-02T00:00:01Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.return_value = 0
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, child), (root, child), (child,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        process.send_signal.assert_called_once_with(1)
        self.assertTrue(terminated)
        self.assertEqual(
            [root, child], guard.terminated
        )
        process.terminate.assert_not_called()
        process.kill.assert_not_called()

    def test_windows_inventory_command_emits_actual_tabs_without_sensitive_fields(self) -> None:
        completed = subprocess.CompletedProcess(
            [],
            0,
            "100\t0\tpython.exe\t2026-09-02T00:00:00Z\n"
            "200\t100\tjava.exe\t2026-09-02T00:00:01Z\n",
            "",
        )
        with patch.object(watchdog, "run_quiet", return_value=completed) as run:
            inventory = watchdog.windows_process_inventory()

        command = run.call_args.args[0]
        powershell = command[-1]
        self.assertIn('"{0}`t{1}`t{2}`t{3:o}"', powershell)
        self.assertNotIn("'{0}`t{1}`t{2}`t{3:o}'", powershell)
        self.assertNotIn("CommandLine", powershell)
        self.assertNotIn("Environment", powershell)
        self.assertEqual((100, 200), tuple(process.pid for process in inventory or ()))

    def test_windows_root_exit_during_ctrl_break_still_verifies_captured_child(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", "2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", "2026-09-02T00:00:01Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.send_signal.side_effect = ProcessLookupError
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, child), (child,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        process.wait.assert_not_called()
        self.assertEqual([child], guard.terminated)

    def test_windows_preacquired_tree_cleans_stdout_descendant_after_root_already_exited(
        self,
    ) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", "2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", "2026-09-02T00:00:01Z"
        )
        guard = RecordingWindowsProcessTreeGuard()
        self.assertTrue(guard.acquire(root))
        self.assertTrue(guard.acquire(child, parent=root))
        process = Mock(pid=100)
        process.poll.return_value = 0

        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((child,), (), ()),
            ),
        ):
            terminated = watchdog.terminate(process, windows_guard=guard)

        self.assertTrue(terminated)
        process.send_signal.assert_not_called()
        self.assertEqual([child], guard.terminated)
        self.assertTrue(guard.closed)

    def test_windows_tree_guard_is_preacquired_before_output_streaming(self) -> None:
        source = WATCHDOG.read_text(encoding="utf-8")
        launch = source.index("process = subprocess.Popen(")
        preacquire = source.index(
            "windows_guard = preacquire_windows_process_tree_guard(process)", launch
        )
        output_stream = source.index("threading.Thread(target=stream_output", preacquire)

        self.assertLess(launch, preacquire)
        self.assertLess(preacquire, output_stream)

    def test_windows_preacquire_retains_root_and_existing_descendant_ownership(
        self,
    ) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", "2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", "2026-09-02T00:00:01Z"
        )
        process = Mock(pid=100)
        guard = RecordingWindowsProcessTreeGuard()

        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(
                watchdog, "windows_process_inventory", return_value=(root, child)
            ),
            patch.object(
                watchdog, "create_windows_process_tree_guard", return_value=guard
            ),
        ):
            captured = watchdog.preacquire_windows_process_tree_guard(process)

        self.assertIs(guard, captured)
        self.assertEqual(root, guard.owned_with_pid(root.pid))
        self.assertEqual(child, guard.owned_with_pid(child.pid))
        self.assertFalse(guard.closed)

    def test_windows_fallback_verification_fails_closed_without_raw_command_data(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", "2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", "2026-09-02T00:00:01Z"
        )
        reused = watchdog.ProcessIdentity(
            200, 1, "other.exe", "gradle-stage", "2026-09-02T00:01:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.return_value = 0
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, child), (root, reused), (reused,), (reused,)),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        self.assertEqual([root], guard.terminated)
        record = watchdog.process_record(child)
        self.assertEqual(
            {"pid", "ppid", "executable", "actor"},
            set(record),
        )
        self.assertNotIn("command", record)
        self.assertNotIn("environment", record)

        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "diagnostic"
            destination.mkdir()
            watchdog.collect_diagnostics(
                args=SimpleNamespace(stage="integrationTest"),
                process=Mock(pid=100),
                original_reason="stage-timeout",
                outcome="process-tree-termination-failed",
                root_complete=False,
                baseline=watchdog.DockerResourceInspection(trusted=True, resources=()),
                observation=watchdog.DockerResourceObservation(
                    trusted=True, current=(), new=(), removed=()
                ),
                runtime=watchdog.RuntimeDiagnostics(destination, (record,), ()),
            )
            process_fields = json.loads(
                (destination / "manifest.json").read_text(encoding="utf-8")
            )["processes"][0]

        self.assertEqual({"pid", "ppid", "executable", "actor"}, set(process_fields))

    def test_windows_inventory_missing_live_root_fails_closed_before_signal(self) -> None:
        process = Mock(pid=100)
        process.poll.return_value = None
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(watchdog, "windows_process_inventory", return_value=()),
            patch.object(watchdog, "run_quiet") as run,
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        process.send_signal.assert_not_called()
        run.assert_not_called()

    def test_windows_late_child_after_ctrl_break_is_discovered_killed_and_verified(self) -> None:
        root = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="2026-09-02T00:00:00.0000000Z",
        )
        child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="2026-09-02T00:00:01.0000000Z",
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root, child), (child,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual(
            [root, child], guard.terminated
        )

    def test_windows_reused_child_pid_is_not_killed_and_fails_closed(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", creation_date="2026-09-02T00:00:01Z"
        )
        reused = watchdog.ProcessIdentity(
            200, 1, "other.exe", "gradle-stage", creation_date="2026-09-02T00:01:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, child), (root, reused), (reused,), (reused,)),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        self.assertEqual([root], guard.terminated)

    def test_windows_late_descendant_chain_is_merged_at_each_kill_stage(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        child = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", creation_date="2026-09-02T00:00:01Z"
        )
        grandchild = watchdog.ProcessIdentity(
            300, 200, "java.exe", "test-worker", creation_date="2026-09-02T00:00:02Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=(
                    (root,),
                    (root, child),
                    (child, grandchild),
                    (grandchild,),
                    (),
                    (),
                ),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual(
            [root, child, grandchild],
            guard.terminated,
        )

    def test_windows_reused_root_pid_is_never_killed_and_fails_closed(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        reused = watchdog.ProcessIdentity(
            100, 1, "other.exe", "gradle-stage", creation_date="2026-09-02T00:01:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.return_value = 0
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (reused,), (reused,)),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        self.assertEqual([], guard.terminated)

    def test_windows_inventory_rejects_missing_or_malformed_creation_date(self) -> None:
        for creation_date in ("", "not-a-date", "2026-09-02 00:00:00"):
            with (
                self.subTest(creation_date=creation_date),
                patch.object(
                    watchdog,
                    "run_quiet",
                    return_value=subprocess.CompletedProcess(
                        [], 0, f"100\t0\tpython.exe\t{creation_date}\n", ""
                    ),
                ) as run,
            ):
                self.assertIsNone(watchdog.windows_process_inventory())
                powershell = run.call_args.args[0][-1]
                self.assertIn("CreationDate", powershell)
                self.assertIn('"{0}`t{1}`t{2}`t{3:o}"', powershell)
                self.assertNotIn("CommandLine", powershell)
                self.assertNotIn("Environment", powershell)

    def test_windows_retained_parent_owns_late_child_after_parent_exit(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        ambiguous = watchdog.ProcessIdentity(
            200, 100, "java.exe", "test-worker", creation_date="2026-09-02T00:00:01Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root,), (ambiguous,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual([root, ambiguous], guard.terminated)

    def test_windows_initial_stale_child_older_than_reused_root_is_excluded(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:01:00Z"
        )
        stale = watchdog.ProcessIdentity(
            200, 100, "other.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, stale), (root, stale), (stale,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual([root], guard.terminated)

    def test_windows_late_candidate_older_than_live_owned_parent_is_not_merged(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:01:00Z"
        )
        stale = watchdog.ProcessIdentity(
            200, 100, "other.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root, stale), (stale,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual([root], guard.terminated)

    def test_windows_root_termination_never_recursively_kills_stale_older_child(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:01:00Z"
        )
        stale = watchdog.ProcessIdentity(
            200, 100, "other.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        completed = subprocess.CompletedProcess([], 0, "", "")
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root, stale), (root, stale), (stale,), (stale,)),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual([root], guard.terminated)

    def test_windows_child_spawned_after_last_root_inventory_is_still_terminated(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        late_child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="2026-09-02T00:00:01Z",
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root,), (late_child,), (), ()),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual(
            [root, late_child], guard.terminated
        )

    def test_windows_late_child_chain_is_owned_after_root_termination(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        late_child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="2026-09-02T00:00:01Z",
        )
        late_grandchild = watchdog.ProcessIdentity(
            300,
            200,
            "java.exe",
            "test-worker",
            creation_date="2026-09-02T00:00:02Z",
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=(
                    (root,),
                    (root,),
                    (late_child,),
                    (late_grandchild,),
                    (),
                    (),
                ),
            ),
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual(
            [root, late_child, late_grandchild],
            guard.terminated,
        )

    def test_windows_guard_retains_exact_handles_until_final_close(self) -> None:
        root = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="1601-01-01T00:00:00.0000000Z",
        )
        late_child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="1601-01-01T00:00:00.0000005Z",
        )
        api = Mock()
        api.open_process.side_effect = (11, 22)
        api.creation_time_ticks.side_effect = lambda handle: {11: 0, 22: 5}[handle]
        api.exit_time_ticks.side_effect = lambda handle: {11: 10, 22: 0}[handle]
        api.wait_for_single_object.side_effect = (
            watchdog.WAIT_TIMEOUT,
            watchdog.WAIT_OBJECT_0,
        )
        api.terminate_process.return_value = True
        api.close_handle.return_value = True

        guard = watchdog.WindowsProcessTreeGuard(api)
        self.assertTrue(guard.acquire(root))
        self.assertTrue(guard.terminate(root))
        self.assertTrue(guard.acquire(late_child, parent=root))
        api.terminate_process.assert_called_once_with(11, 1)
        self.assertEqual(
            [call(11, 0), call(11, watchdog.WINDOWS_TERMINATION_TIMEOUT_MILLIS)],
            api.wait_for_single_object.call_args_list,
        )
        api.close_handle.assert_not_called()

        self.assertTrue(guard.close())
        self.assertEqual([call(22), call(11)], api.close_handle.call_args_list)

    def test_windows_guard_parent_exit_tick_boundary_is_exact(self) -> None:
        root = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="1601-01-01T00:00:00.0000000Z",
        )
        for child_tick, expected in ((9, True), (10, True), (11, False)):
            with self.subTest(child_tick=child_tick):
                child = watchdog.ProcessIdentity(
                    200,
                    100,
                    "java.exe",
                    "test-worker",
                    creation_date=f"1601-01-01T00:00:00.{child_tick:07d}Z",
                )
                api = Mock()
                api.open_process.side_effect = (11, 22)
                api.creation_time_ticks.side_effect = lambda handle, tick=child_tick: {
                    11: 0,
                    22: tick,
                }[handle]
                api.exit_time_ticks.return_value = 10
                api.wait_for_single_object.side_effect = (
                    watchdog.WAIT_TIMEOUT,
                    watchdog.WAIT_OBJECT_0,
                )
                api.terminate_process.return_value = True
                api.close_handle.return_value = True
                guard = watchdog.WindowsProcessTreeGuard(api)

                self.assertTrue(guard.acquire(root))
                self.assertTrue(guard.terminate(root))
                self.assertIs(expected, guard.acquire(child, parent=root))
                self.assertTrue(guard.close())

    def test_windows_guard_fails_closed_on_acquire_time_mismatch_and_close_failures(self) -> None:
        root = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="1601-01-01T00:00:00.0000000Z",
        )
        child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="1601-01-01T00:00:00.0000001Z",
        )

        unavailable = Mock()
        unavailable.open_process.return_value = 0
        self.assertFalse(watchdog.WindowsProcessTreeGuard(unavailable).acquire(root))

        mismatch = Mock()
        mismatch.open_process.return_value = 11
        mismatch.creation_time_ticks.return_value = 1
        mismatch.close_handle.return_value = True
        mismatch_guard = watchdog.WindowsProcessTreeGuard(mismatch)
        self.assertFalse(mismatch_guard.acquire(root))
        mismatch.terminate_process.assert_not_called()
        mismatch.close_handle.assert_called_once_with(11)

        time_failure = Mock()
        time_failure.open_process.return_value = 11
        time_failure.creation_time_ticks.side_effect = OSError("untrusted process time")
        time_failure.close_handle.return_value = True
        time_failure_guard = watchdog.WindowsProcessTreeGuard(time_failure)
        self.assertFalse(time_failure_guard.acquire(root))
        time_failure.close_handle.assert_called_once_with(11)

        unknown_exit = Mock()
        unknown_exit.open_process.side_effect = (11, 22)
        unknown_exit.creation_time_ticks.side_effect = (0, 1)
        unknown_exit.exit_time_ticks.return_value = None
        unknown_exit.wait_for_single_object.side_effect = (
            watchdog.WAIT_TIMEOUT,
            watchdog.WAIT_OBJECT_0,
        )
        unknown_exit.terminate_process.return_value = True
        unknown_exit.close_handle.return_value = True
        unknown_exit_guard = watchdog.WindowsProcessTreeGuard(unknown_exit)
        self.assertTrue(unknown_exit_guard.acquire(root))
        self.assertTrue(unknown_exit_guard.terminate(root))
        self.assertFalse(unknown_exit_guard.acquire(child, parent=root))

        unknown_exit.close_handle.return_value = False
        self.assertFalse(unknown_exit_guard.close())

    def test_windows_termination_requires_two_empty_inventories_before_success(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        late_child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="2026-09-02T00:00:01Z",
        )
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root,), (), (late_child,), (), ()),
            ) as inventory,
            patch.object(
                watchdog,
                "create_windows_process_tree_guard",
                return_value=(guard := RecordingWindowsProcessTreeGuard()),
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertTrue(terminated)
        self.assertEqual(
            [root, late_child], guard.terminated
        )
        self.assertEqual(6, inventory.call_count)

    def test_windows_termination_fails_closed_when_guard_close_fails(self) -> None:
        root = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        guard = RecordingWindowsProcessTreeGuard()
        guard.close_result = False
        process = Mock(pid=100)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("stage", 5)
        with (
            patch.object(watchdog.os, "name", "nt"),
            patch.object(watchdog.signal, "CTRL_BREAK_EVENT", 1, create=True),
            patch.object(
                watchdog,
                "windows_process_inventory",
                side_effect=((root,), (root,), (), ()),
            ),
            patch.object(
                watchdog, "create_windows_process_tree_guard", return_value=guard
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        self.assertTrue(guard.closed)

    def test_windows_exact_termination_mismatch_fails_closed_without_unscoped_stop(self) -> None:
        owned = watchdog.ProcessIdentity(
            100, 0, "python.exe", "gradle-stage", creation_date="2026-09-02T00:00:00Z"
        )
        api = Mock()
        api.open_process.return_value = 11
        api.creation_time_ticks.return_value = 1

        self.assertFalse(watchdog.terminate_windows_incarnation(owned, api))

        api.terminate_process.assert_not_called()
        api.wait_for_single_object.assert_not_called()
        api.close_handle.assert_called_once_with(11)

    def test_windows_exact_termination_fails_closed_when_handle_close_is_unverified(self) -> None:
        owned = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="1601-01-01T00:00:00.0000000Z",
        )
        api = Mock()
        api.open_process.return_value = 11
        api.creation_time_ticks.return_value = 0
        api.terminate_process.return_value = True
        api.wait_for_single_object.return_value = watchdog.WAIT_OBJECT_0
        api.close_handle.return_value = False

        self.assertFalse(watchdog.terminate_windows_incarnation(owned, api))

        api.close_handle.assert_called_once_with(11)

    def test_creation_date_to_filetime_ticks_preserves_fraction_and_offset(self) -> None:
        self.assertEqual(
            0, watchdog.creation_date_to_filetime_ticks("1601-01-01T00:00:00.0000000Z")
        )
        self.assertEqual(
            1, watchdog.creation_date_to_filetime_ticks("1601-01-01T00:00:00.0000001Z")
        )
        self.assertEqual(
            0,
            watchdog.creation_date_to_filetime_ticks(
                "1601-01-01T09:00:00.0000000+09:00"
            ),
        )
        self.assertEqual(
            116444736000000000,
            watchdog.creation_date_to_filetime_ticks("1970-01-01T00:00:00.0000000Z"),
        )

    def test_creation_ordering_preserves_single_filetime_tick_boundaries(self) -> None:
        parent = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="2026-09-02T00:00:00.0000002Z",
        )

        for creation_date, expected in (
            ("2026-09-02T00:00:00.0000001Z", False),
            ("2026-09-02T00:00:00.0000002Z", True),
            ("2026-09-02T00:00:00.0000003Z", True),
            ("2026-09-02T09:00:00.0000002+09:00", True),
        ):
            with self.subTest(creation_date=creation_date):
                child = watchdog.ProcessIdentity(
                    200,
                    100,
                    "java.exe",
                    "test-worker",
                    creation_date=creation_date,
                )
                self.assertIs(
                    expected,
                    watchdog.creation_not_before(child, parent),
                )

    def test_windows_exact_root_and_child_termination_bind_validated_creation_date(self) -> None:
        root = watchdog.ProcessIdentity(
            100,
            0,
            "python.exe",
            "gradle-stage",
            creation_date="1601-01-01T00:00:00.0000000Z",
        )
        child = watchdog.ProcessIdentity(
            200,
            100,
            "java.exe",
            "test-worker",
            creation_date="1601-01-01T00:00:00.0000001Z",
        )
        api = Mock()
        api.open_process.side_effect = (11, 22)
        api.creation_time_ticks.side_effect = (0, 1)
        api.terminate_process.return_value = True
        api.wait_for_single_object.return_value = watchdog.WAIT_OBJECT_0

        self.assertTrue(watchdog.terminate_windows_incarnation(root, api))
        self.assertTrue(watchdog.terminate_windows_incarnation(child, api))

        self.assertEqual(
            [call(100, watchdog.WINDOWS_PROCESS_ACCESS), call(200, watchdog.WINDOWS_PROCESS_ACCESS)],
            api.open_process.call_args_list,
        )
        self.assertEqual([call(11, 1), call(22, 1)], api.terminate_process.call_args_list)
        self.assertEqual(
            [call(11, 5000), call(22, 5000)],
            api.wait_for_single_object.call_args_list,
        )
        self.assertEqual([call(11), call(22)], api.close_handle.call_args_list)

    def test_posix_termination_verifies_group_after_sigint_even_when_root_already_exited(
        self,
    ) -> None:
        root = watchdog.PosixProcessIdentity(100, 1, 100, "root-start")
        child = watchdog.PosixProcessIdentity(200, 100, 100, "child-start")
        guard = watchdog.PosixProcessGroupGuard(root)
        self.assertTrue(guard.discover((root, child)))
        process = Mock(pid=100)
        process.poll.return_value = 0
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog.os, "killpg") as killpg,
            patch.object(
                watchdog,
                "posix_process_inventory",
                return_value=(child,),
            ),
            patch.object(
                watchdog,
                "wait_for_posix_process_group_exit",
                side_effect=(False, False, True),
            ) as wait_for_exit,
        ):
            terminated = watchdog.terminate(process, posix_guard=guard)

        self.assertTrue(terminated)
        self.assertEqual(
            [
                call(100, signal.SIGINT),
                call(100, signal.SIGTERM),
                call(100, signal.SIGKILL),
            ],
            killpg.call_args_list,
        )
        self.assertEqual(3, wait_for_exit.call_count)

    def test_posix_termination_revalidates_incarnation_before_signal(self) -> None:
        root = watchdog.PosixProcessIdentity(100, 100, 100, "root-start")
        owned_child = watchdog.PosixProcessIdentity(200, 100, 100, "child-start")
        reused_child = watchdog.PosixProcessIdentity(200, 100, 100, "replacement-start")
        guard = watchdog.PosixProcessGroupGuard(root)
        self.assertTrue(guard.discover((root, owned_child)))
        process = Mock(pid=100)
        process.poll.return_value = None
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog.os, "killpg") as killpg,
            patch.object(
                watchdog,
                "posix_process_inventory",
                side_effect=((root, owned_child), (root, reused_child)),
            ),
        ):
            terminated = watchdog.terminate(process, posix_guard=guard)

        self.assertFalse(terminated)
        killpg.assert_not_called()

    def test_posix_termination_fails_closed_when_identity_inventory_is_untrusted(self) -> None:
        process = Mock(pid=100)
        process.poll.return_value = None
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog.os, "killpg") as killpg,
            patch.object(
                watchdog,
                "preacquire_posix_process_group_guard",
                return_value=None,
            ),
            patch.object(
                watchdog,
                "posix_process_inventory",
                return_value=None,
            ),
        ):
            terminated = watchdog.terminate(process)

        self.assertFalse(terminated)
        killpg.assert_not_called()

    def test_normal_posix_completion_without_guard_still_requires_residue_proof(self) -> None:
        process = Mock(pid=100)
        process.poll.return_value = 0
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())
        clean = watchdog.DockerResourceObservation(
            trusted=True, current=(), new=(), removed=()
        )
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog, "terminate", return_value=False) as terminate,
            patch.object(watchdog, "observe_docker_resources", return_value=clean),
            patch.object(
                watchdog, "collect_diagnostics", return_value=Path("diagnostic")
            ) as collect,
        ):
            result = watchdog.finalize_terminal(
                args=SimpleNamespace(stage="integrationTest"),
                process=process,
                original_reason=None,
                requested_exit_code=0,
                root_complete=True,
                baseline=baseline,
                posix_guard=None,
            )

        self.assertEqual(watchdog.TERMINATION_EXIT_CODE, result)
        terminate.assert_called_once_with(process)
        self.assertEqual(
            "process-tree-termination-failed", collect.call_args.kwargs["outcome"]
        )

    def test_normal_posix_completion_without_guard_rejects_untrusted_inventory_and_descendant(
        self,
    ) -> None:
        process = Mock(pid=100)
        process.poll.return_value = 0
        descendant = watchdog.PosixProcessIdentity(
            200, 1, 100, "linux-proc-start-ticks:9002"
        )
        for inventory in (None, (descendant,)):
            with self.subTest(inventory=inventory):
                with (
                    patch.object(watchdog.os, "name", "posix"),
                    patch.object(
                        watchdog,
                        "preacquire_posix_process_group_guard",
                        return_value=None,
                    ),
                    patch.object(
                        watchdog, "posix_process_inventory", return_value=inventory
                    ),
                    patch.object(watchdog.os, "killpg") as killpg,
                ):
                    terminated = watchdog.terminate(process)

                self.assertFalse(terminated)
                killpg.assert_not_called()

    def test_linux_posix_incarnation_uses_proc_start_ticks_beyond_display_second(
        self,
    ) -> None:
        stat = "100 (java worker) " + " ".join(["S"] + ["0"] * 18 + ["987654321"])
        with (
            patch.object(watchdog.sys, "platform", "linux"),
            patch.object(Path, "read_text", return_value=stat),
        ):
            identity = watchdog.posix_process_start_identity(100)

        self.assertEqual("linux-proc-start-ticks:987654321", identity)

    def test_darwin_posix_incarnation_preserves_microseconds_within_same_display_second(
        self,
    ) -> None:
        with (
            patch.object(watchdog.sys, "platform", "darwin"),
            patch.object(
                watchdog,
                "darwin_process_start_identity",
                side_effect=(
                    "darwin-proc-start-micros:1700000000123456",
                    "darwin-proc-start-micros:1700000000654321",
                ),
            ),
        ):
            first = watchdog.posix_process_start_identity(100)
            replacement = watchdog.posix_process_start_identity(100)

        self.assertNotEqual(first, replacement)

    def test_posix_guard_state_distinguishes_trusted_absence_from_untrusted_inventory(
        self,
    ) -> None:
        process = Mock(pid=100)
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(
                watchdog, "posix_process_start_identity", return_value=None
            ),
            patch.object(watchdog, "posix_process_inventory", return_value=()),
        ):
            absent = watchdog.preacquire_posix_process_group_guard_state(process)
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(
                watchdog, "posix_process_start_identity", return_value=None
            ),
            patch.object(watchdog, "posix_process_inventory", return_value=None),
        ):
            untrusted = watchdog.preacquire_posix_process_group_guard_state(process)

        self.assertEqual(
            watchdog.PosixProcessGroupGuardState(trusted=True, guard=None), absent
        )
        self.assertEqual(
            watchdog.PosixProcessGroupGuardState(trusted=False, guard=None), untrusted
        )

    def test_posix_guard_preacquisition_revalidates_root_identity_around_getpgid(self) -> None:
        process = Mock(pid=100)
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog.os, "getpgid", return_value=100),
            patch.object(
                watchdog,
                "posix_process_start_identity",
                side_effect=(
                    "linux-proc-start-ticks:100001",
                    "linux-proc-start-ticks:100002",
                ),
            ),
            patch.object(watchdog, "posix_process_inventory", return_value=None),
        ):
            state = watchdog.preacquire_posix_process_group_guard_state(process)

        self.assertEqual(
            watchdog.PosixProcessGroupGuardState(trusted=False, guard=None), state
        )

    def test_posix_guard_rejects_same_second_reuse_before_next_signal(self) -> None:
        root = watchdog.PosixProcessIdentity(
            100, 1, 100, "linux-proc-start-ticks:100001"
        )
        child = watchdog.PosixProcessIdentity(
            200, 100, 100, "linux-proc-start-ticks:200001"
        )
        replacement = watchdog.PosixProcessIdentity(
            200, 100, 100, "linux-proc-start-ticks:200002"
        )
        guard = watchdog.PosixProcessGroupGuard(root)
        self.assertTrue(guard.discover((root, child)))
        process = Mock(pid=100)
        process.poll.return_value = 0
        with (
            patch.object(watchdog.os, "name", "posix"),
            patch.object(watchdog.os, "killpg") as killpg,
            patch.object(
                watchdog,
                "posix_process_inventory",
                side_effect=((root, child), (root, child), (root, replacement)),
            ),
            patch.object(
                watchdog, "wait_for_posix_process_group_exit", return_value=False
            ),
        ):
            terminated = watchdog.terminate(process, posix_guard=guard)

        self.assertFalse(terminated)
        self.assertEqual([call(100, signal.SIGINT)], killpg.call_args_list)

    def test_posix_inventory_fails_identity_closed_when_high_resolution_metadata_is_unavailable(
        self,
    ) -> None:
        ps = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="100 1 100 S\n", stderr=""
        )
        with (
            patch.object(watchdog, "run_quiet", return_value=ps),
            patch.object(
                watchdog, "posix_process_start_identity", return_value=None
            ),
        ):
            inventory = watchdog.posix_process_inventory()

        self.assertEqual((watchdog.PosixProcessIdentity(100, 1, 100, None, "S"),), inventory)
        process = Mock(pid=100)
        process.poll.return_value = None
        with patch.object(watchdog, "posix_process_inventory", return_value=inventory):
            self.assertIsNone(watchdog.preacquire_posix_process_group_guard(process))

    def test_posix_guard_ignores_reaped_zombie_snapshot_without_start_identity(self) -> None:
        root = watchdog.PosixProcessIdentity(
            100, 1, 100, "darwin-proc-start-micros:1700000000123456"
        )
        zombie = watchdog.PosixProcessIdentity(100, 1, 100, None, "Z")
        guard = watchdog.PosixProcessGroupGuard(root)

        self.assertTrue(guard.discover((zombie,)))

    def test_posix_exit_wait_rechecks_after_transient_inventory_failure(self) -> None:
        process = Mock()
        with (
            patch.object(
                watchdog,
                "posix_process_group_members",
                side_effect=(None, ()),
            ) as members,
            patch.object(
                watchdog,
                "posix_process_group_exists",
                return_value=True,
            ) as group_exists,
            patch.object(watchdog.time, "sleep"),
            patch.object(
                watchdog.time,
                "monotonic",
                side_effect=(0.0, 0.1, 0.2),
            ),
        ):
            exited = watchdog.wait_for_posix_process_group_exit(
                process, 100, timeout_seconds=1.0
            )

        self.assertTrue(exited)
        self.assertEqual(2, members.call_count)
        group_exists.assert_called_once_with(100)

    def test_posix_guard_exit_wait_retries_untrusted_post_signal_snapshot_without_resignaling(
        self,
    ) -> None:
        process = Mock()
        guard = Mock()
        guard.revalidate.side_effect = (None, ())
        with (
            patch.object(watchdog.time, "sleep"),
            patch.object(
                watchdog.time,
                "monotonic",
                side_effect=(0.0, 0.1, 0.2),
            ),
        ):
            exited = watchdog.wait_for_posix_process_group_exit(
                process, 100, timeout_seconds=1.0, posix_guard=guard
            )

        self.assertTrue(exited)
        self.assertEqual(2, guard.revalidate.call_count)

    def test_termination_verification_failure_has_distinct_fail_closed_outcome(self) -> None:
        process = Mock(pid=100)
        process.poll.return_value = None
        baseline = watchdog.DockerResourceInspection(trusted=True, resources=())
        clean = watchdog.DockerResourceObservation(
            trusted=True, current=(), new=(), removed=()
        )
        runtime = watchdog.RuntimeDiagnostics(Path("diagnostic"), (), ())
        with (
            patch.object(
                watchdog, "capture_runtime_diagnostics", return_value=runtime
            ),
            patch.object(watchdog, "terminate", return_value=False),
            patch.object(watchdog, "observe_docker_resources", return_value=clean),
            patch.object(
                watchdog, "collect_diagnostics", return_value=Path("diagnostic")
            ) as collect,
        ):
            result = watchdog.finalize_terminal(
                args=SimpleNamespace(stage="integrationTest"),
                process=process,
                original_reason="stage-timeout",
                requested_exit_code=124,
                root_complete=False,
                baseline=baseline,
            )

        self.assertEqual(watchdog.TERMINATION_EXIT_CODE, result)
        self.assertEqual(
            "process-tree-termination-failed", collect.call_args.kwargs["outcome"]
        )

    @staticmethod
    def _watchdog_environment(temporary: Path) -> dict[str, str]:
        binary_directory = temporary / "bin"
        binary_directory.mkdir()
        docker = binary_directory / "docker"
        docker.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        docker.chmod(0o755)
        return {"PATH": f"{binary_directory}:/usr/bin:/bin:/usr/sbin", "LC_ALL": "C"}

    @staticmethod
    def _block(source: str, marker: str) -> str:
        start = source.index(marker)
        opening = source.index("{", start)
        depth = 0
        for index in range(opening, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return source[start : index + 1]
        raise AssertionError(f"닫히지 않은 Gradle block: {marker}")


if __name__ == "__main__":
    unittest.main()
