#!/usr/bin/env python3
"""Run one Gradle stage with bounded post-suite diagnostics."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_POST_SUITE_TIMEOUT_SECONDS = 120
TIMEOUT_EXIT_CODE = 124
MARKER_EXIT_CODE = 125
RESIDUE_EXIT_CODE = 126
INSPECTION_EXIT_CODE = 127
DIAGNOSTIC_EXIT_CODE = 128
TERMINATION_EXIT_CODE = 129
SAFE_STAGE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
SHA256_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
MAX_RESOURCE_FIELD_LENGTH = 160
MAX_RAW_RESOURCE_FIELD_LENGTH = 4096
MAX_CAPTURED_DESCENDANTS = 256
MAX_WINDOWS_TERMINATION_STAGES = MAX_CAPTURED_DESCENDANTS * 2 + 4
CREATION_DATE = re.compile(
    r"^(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})T"
    r"(?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})"
    r"(?:\.(?P<fraction>\d{1,7}))?(?P<zone>Z|[+-]\d{2}:\d{2})$"
)
WINDOWS_PROCESS_ACCESS = 0x0001 | 0x1000 | 0x00100000
WAIT_OBJECT_0 = 0
WAIT_TIMEOUT = 258
WINDOWS_TERMINATION_TIMEOUT_MILLIS = 5000
WINDOWS_QUIET_INVENTORIES = 2
POSIX_TERMINATION_TIMEOUT_SECONDS = 2.0
POSIX_PROCESS_POLL_SECONDS = 0.05
FILETIME_TICKS_PER_SECOND = 10_000_000
FILETIME_EPOCH = dt.datetime(1601, 1, 1, tzinfo=dt.timezone.utc)
ALLOWLISTED_DOCKER_LABELS = frozenset(
    {"org.testcontainers", "com.docker.compose.project", "com.docker.compose.service"}
)
APPROVED_TESTCONTAINERS_IMAGE_PROVENANCE = {
    # Registry manifest digest for the compose/factory-pinned linux/amd64 image.
    "postgis/postgis:16-3.4": (
        "sha256:44126d872ac91993766c341e369c539e8196614321765d36a6f1bab0419a5fa5"
    ),
    # Registry manifest-list digest shared by the CI linux/amd64 and local arm64 images.
    "testcontainers/ryuk:0.14.0": (
        "sha256:7c1a8a9a47c780ed0f983770a662f80deb115d95cce3e2daa3d12115b8cd28f0"
    ),
}
POSIX_GUARD_UNSET = object()
DOCKER_RESOURCE_COMMANDS = {
    "container": [
        "docker",
        "container",
        "ls",
        "-a",
        "--no-trunc",
        "--format",
        "{{.ID}}\t{{.Names}}\t{{.State}}\t{{.Status}}",
    ],
    "volume": ["docker", "volume", "ls", "--format", "{{.Name}}\t{{.Name}}\tvolume"],
    "network": [
        "docker",
        "network",
        "ls",
        "--no-trunc",
        "--format",
        "{{.ID}}\t{{.Name}}\t{{.Driver}}",
    ],
    "image": [
        "docker",
        "image",
        "ls",
        "-a",
        "--digests",
        "--no-trunc",
        "--format",
        "{{.ID}}\t{{.Repository}}:{{.Tag}}\t{{.Digest}}",
    ],
}


@dataclass(frozen=True)
class DockerResourceIdentity:
    kind: str
    id: str
    name: str
    status: str
    labels: tuple[tuple[str, str], ...]
    state: str | None = None
    health: str | None = None


@dataclass(frozen=True)
class DockerResourceStateChange:
    before: DockerResourceIdentity
    after: DockerResourceIdentity


@dataclass(frozen=True)
class DockerResourceInspection:
    trusted: bool
    resources: tuple[DockerResourceIdentity, ...]


@dataclass(frozen=True)
class DockerResourceObservation:
    trusted: bool
    current: tuple[DockerResourceIdentity, ...]
    new: tuple[DockerResourceIdentity, ...]
    removed: tuple[DockerResourceIdentity, ...]
    state_changed: tuple[DockerResourceStateChange, ...] = ()
    cached: tuple[DockerResourceIdentity, ...] = ()


@dataclass(frozen=True)
class ProcessIdentity:
    pid: int
    ppid: int
    name: str
    actor: str
    creation_date: str | None = None


@dataclass(frozen=True)
class PosixProcessIdentity:
    pid: int
    ppid: int = field(compare=False)
    pgid: int
    started_at: str | None
    state: str = field(default="", compare=False)


@dataclass(frozen=True)
class PosixProcessGroupGuardState:
    trusted: bool
    guard: "PosixProcessGroupGuard | None"


class PosixProcessGroupGuard:
    def __init__(self, root: PosixProcessIdentity) -> None:
        self.root = root
        self.owned: dict[int, PosixProcessIdentity] = {root.pid: root}

    def discover(self, inventory: tuple[PosixProcessIdentity, ...]) -> bool:
        current_by_pid = {
            item.pid: item for item in inventory if not item.state.startswith("Z")
        }
        if any(
            item is not None and item != owned
            for pid, owned in self.owned.items()
            if (item := current_by_pid.get(pid)) is not None
        ):
            return False
        continuity = current_by_pid.get(self.root.pid) == self.root or any(
            current_by_pid.get(pid) == owned
            for pid, owned in self.owned.items()
            if pid != self.root.pid
        )
        candidates = tuple(
            item
            for item in inventory
            if item.pgid == self.root.pgid and not item.state.startswith("Z")
        )
        if any(item.started_at is None for item in candidates):
            return False
        if candidates and not continuity:
            return False
        self.owned.update((item.pid, item) for item in candidates)
        return True

    def revalidate(self) -> tuple[PosixProcessIdentity, ...] | None:
        inventory = posix_process_inventory(self.root.pgid)
        if inventory is None or not self.discover(inventory):
            return None
        members = tuple(
            item
            for item in inventory
            if item.pgid == self.root.pgid and not item.state.startswith("Z")
        )
        if any(self.owned.get(item.pid) != item for item in members):
            return None
        return members


@dataclass(frozen=True)
class RuntimeDiagnostics:
    destination: Path
    processes: tuple[dict[str, object], ...]
    thread_dumps: tuple[str, ...]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", required=True)
    parser.add_argument("--timeout-seconds", required=True, type=float)
    parser.add_argument(
        "--post-suite-timeout-seconds",
        type=float,
        default=DEFAULT_POST_SUITE_TIMEOUT_SECONDS,
    )
    parser.add_argument("--expected-marker", required=True)
    parser.add_argument("--diagnostics-dir", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not SAFE_STAGE.fullmatch(args.stage):
        parser.error("stage는 안전한 식별자여야 합니다.")
    if args.timeout_seconds <= 0 or args.post_suite_timeout_seconds <= 0:
        parser.error("timeout은 0보다 커야 합니다.")
    if args.command[:1] == ["--"]:
        args.command = args.command[1:]
    if not args.command:
        parser.error("실행할 명령이 필요합니다.")
    return args


def run_quiet(command: list[str], timeout: float = 5) -> subprocess.CompletedProcess[str] | None:
    try:
        return subprocess.run(
            command,
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError):
        return None


def bounded_resource_field(value: str) -> str:
    return "".join(character for character in value if character.isprintable())[
        :MAX_RESOURCE_FIELD_LENGTH
    ]


def validate_raw_resource_field(value: str) -> str:
    if (
        not value
        or len(value) > MAX_RAW_RESOURCE_FIELD_LENGTH
        or any(not character.isprintable() or character in "\t\r\n" for character in value)
    ):
        raise ValueError("untrusted Docker resource identity")
    return value


def inspect_docker_resources() -> DockerResourceInspection:
    resources = []
    for kind, command in DOCKER_RESOURCE_COMMANDS.items():
        result = run_quiet(command, timeout=3)
        if result is None or result.returncode != 0:
            return DockerResourceInspection(trusted=False, resources=())
        for line in result.stdout.splitlines():
            parts = line.split("\t", 3)
            expected_parts = 4 if kind == "container" else 3
            if len(parts) != expected_parts:
                return DockerResourceInspection(trusted=False, resources=())
            identifier, name = parts[:2]
            state = parts[2] if kind == "container" else None
            status = parts[3] if kind == "container" else parts[2]
            try:
                resources.append(
                    DockerResourceIdentity(
                        kind=kind,
                        id=validate_raw_resource_field(identifier),
                        name=validate_raw_resource_field(name),
                        status=validate_raw_resource_field(status),
                        labels=(),
                        state=(validate_raw_resource_field(state).lower() if state else None),
                        health=container_health(status) if kind == "container" else None,
                    )
                )
            except ValueError:
                return DockerResourceInspection(trusted=False, resources=())
    return DockerResourceInspection(trusted=True, resources=tuple(resources))


def container_health(status: str) -> str:
    lowered = status.lower()
    if "(unhealthy)" in lowered:
        return "unhealthy"
    if "(healthy)" in lowered:
        return "healthy"
    if "(health: starting)" in lowered:
        return "starting"
    return "none"


def is_reusable_dependency_image(resource: DockerResourceIdentity) -> bool:
    expected_digest = APPROVED_TESTCONTAINERS_IMAGE_PROVENANCE.get(resource.name)
    return (
        resource.kind == "image"
        and SHA256_DIGEST.fullmatch(resource.id) is not None
        and expected_digest is not None
        and expected_digest == resource.status
    )


def observe_docker_resources(
    baseline: DockerResourceInspection, grace_seconds: float = 15
) -> DockerResourceObservation:
    if not baseline.trusted:
        return DockerResourceObservation(trusted=False, current=(), new=(), removed=())
    baseline_identities = {
        (resource.kind, resource.id, resource.name): resource
        for resource in baseline.resources
    }
    baseline_image_ids = {
        resource.id for resource in baseline.resources if resource.kind == "image"
    }
    deadline = time.monotonic() + grace_seconds
    while True:
        inspection = inspect_docker_resources()
        if not inspection.trusted:
            return DockerResourceObservation(trusted=False, current=(), new=(), removed=())
        current_identities = {
            (resource.kind, resource.id, resource.name): resource
            for resource in inspection.resources
        }
        added = tuple(
            resource
            for resource in inspection.resources
            if (resource.kind, resource.id, resource.name) not in baseline_identities
        )
        cached = tuple(
            resource
            for resource in added
            if is_reusable_dependency_image(resource)
            and resource.id not in baseline_image_ids
        )
        residue = tuple(resource for resource in added if resource not in cached)
        removed = tuple(
            resource
            for identity, resource in baseline_identities.items()
            if identity not in current_identities
        )
        state_changed = tuple(
            DockerResourceStateChange(baseline_resource, current_identities[identity])
            for identity, baseline_resource in baseline_identities.items()
            if identity in current_identities
            and baseline_resource.kind == "container"
            and (
                baseline_resource.state,
                baseline_resource.health,
            )
            != (
                current_identities[identity].state,
                current_identities[identity].health,
            )
        )
        if (
            not residue and not removed and not state_changed
        ) or time.monotonic() >= deadline:
            return DockerResourceObservation(
                trusted=True,
                current=inspection.resources,
                new=residue,
                removed=removed,
                state_changed=state_changed,
                cached=cached,
            )
        time.sleep(0.25)


def process_snapshot(root_pid: int) -> tuple[list[dict[str, object]], list[int]]:
    if os.name == "nt":
        inventory = windows_process_inventory()
        if inventory is None:
            return [], []
        descendants = descendant_processes(inventory, root_pid)
        if descendants is None:
            return [], []
        return (
            [process_record(process) for process in descendants],
            [process.pid for process in descendants if process.actor == "test-worker"],
        )
    result = run_quiet(
        ["ps", "-axo", "pid=,ppid=,pgid=,comm=,command="], timeout=3
    )
    if result is None or result.returncode != 0:
        return [], []
    raw: dict[int, tuple[int, int, str, str]] = {}
    for line in result.stdout.splitlines():
        parts = line.strip().split(None, 4)
        if (
            len(parts) == 5
            and parts[0].isdigit()
            and parts[1].isdigit()
            and parts[2].isdigit()
        ):
            raw[int(parts[0])] = (
                int(parts[1]),
                int(parts[2]),
                parts[3],
                parts[4],
            )
    descendants = {
        pid for pid, (_, pgid, _, _) in raw.items() if pgid == root_pid
    }
    descendants.add(root_pid)
    changed = True
    while changed:
        changed = False
        for pid, (ppid, _, _, _) in raw.items():
            if ppid in descendants and pid not in descendants:
                descendants.add(pid)
                changed = True
    rows = []
    test_workers = []
    for pid in sorted(descendants):
        ppid, _, executable, command = raw.get(pid, (0, root_pid, "unknown", ""))
        actor = "test-worker" if "Gradle Test Executor" in command else "gradle-stage"
        if actor == "test-worker":
            test_workers.append(pid)
        rows.append(
            {
                "pid": pid,
                "ppid": ppid,
                "actor": actor,
                "executable": Path(executable).name,
            }
        )
    return rows, test_workers


def windows_process_inventory() -> tuple[ProcessIdentity, ...] | None:
    result = run_quiet(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-CimInstance Win32_Process | ForEach-Object { "
            '"{0}`t{1}`t{2}`t{3:o}" -f '
            "$_.ProcessId, $_.ParentProcessId, $_.Name, $_.CreationDate }",
        ],
        timeout=5,
    )
    if result is None or result.returncode != 0:
        return None
    processes = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if (
            len(parts) != 4
            or not parts[0].isdigit()
            or not parts[1].isdigit()
        ):
            return None
        try:
            name = validate_raw_resource_field(parts[2])
            creation_date = validate_creation_date(parts[3])
        except ValueError:
            return None
        processes.append(
            ProcessIdentity(
                pid=int(parts[0]),
                ppid=int(parts[1]),
                name=name,
                actor="test-worker" if name.lower() == "java.exe" else "gradle-stage",
                creation_date=creation_date,
            )
        )
    return tuple(processes)


def validate_creation_date(value: str) -> str:
    if not CREATION_DATE.fullmatch(value):
        raise ValueError("untrusted process creation date")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("untrusted process creation date")
    return value


def creation_date_to_filetime_ticks(value: str) -> int:
    validate_creation_date(value)
    match = CREATION_DATE.fullmatch(value)
    assert match is not None
    zone = match.group("zone")
    if zone == "Z":
        timezone = dt.timezone.utc
    else:
        sign = 1 if zone[0] == "+" else -1
        offset = dt.timedelta(hours=int(zone[1:3]), minutes=int(zone[4:6]))
        timezone = dt.timezone(sign * offset)
    wall_time = dt.datetime(
        int(match.group("year")),
        int(match.group("month")),
        int(match.group("day")),
        int(match.group("hour")),
        int(match.group("minute")),
        int(match.group("second")),
        tzinfo=timezone,
    )
    elapsed = wall_time.astimezone(dt.timezone.utc) - FILETIME_EPOCH
    fraction_ticks = int((match.group("fraction") or "0").ljust(7, "0"))
    ticks = (
        (elapsed.days * 86_400 + elapsed.seconds) * FILETIME_TICKS_PER_SECOND
        + fraction_ticks
    )
    if ticks < 0:
        raise ValueError("process creation date predates FILETIME epoch")
    return ticks


def descendant_processes(
    inventory: tuple[ProcessIdentity, ...], root_pid: int
) -> tuple[ProcessIdentity, ...] | None:
    roots = {process.pid: process for process in inventory if process.pid == root_pid}
    descendants = dict(roots)
    changed = True
    while changed:
        changed = False
        for process in inventory:
            parent = descendants.get(process.ppid)
            if (
                parent is not None
                and process.pid not in descendants
                and creation_not_before(process, parent) is True
            ):
                descendants[process.pid] = process
                changed = True
                if len(descendants) > MAX_CAPTURED_DESCENDANTS + 1:
                    return None
    return tuple(process for process in inventory if process.pid in descendants)


def creation_not_before(child: ProcessIdentity, parent: ProcessIdentity) -> bool | None:
    if child.creation_date is None or parent.creation_date is None:
        return None
    try:
        child_created = creation_date_to_filetime_ticks(child.creation_date)
        parent_created = creation_date_to_filetime_ticks(parent.creation_date)
    except (OverflowError, ValueError):
        return None
    return child_created >= parent_created


def process_record(process: ProcessIdentity) -> dict[str, object]:
    return {
        "pid": process.pid,
        "ppid": process.ppid,
        "executable": bounded_resource_field(process.name),
        "actor": process.actor,
    }


def disk_snapshot(path: Path) -> dict[str, int]:
    usage = shutil.disk_usage(path)
    return {"totalBytes": usage.total, "usedBytes": usage.used, "freeBytes": usage.free}


def capture_runtime_diagnostics(
    args: argparse.Namespace, process: subprocess.Popen[str]
) -> RuntimeDiagnostics:
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    destination = args.diagnostics_dir / f"{args.stage}-{timestamp}-{process.pid}"
    destination.mkdir(parents=True, exist_ok=False)
    processes, test_workers = process_snapshot(process.pid)
    thread_dumps = []
    for pid in test_workers:
        output = destination / f"jcmd-{pid}-thread-print.txt"
        result = run_quiet(["jcmd", str(pid), "Thread.print", "-l"], timeout=10)
        if result is not None:
            output.write_text(result.stdout + result.stderr, encoding="utf-8")
            thread_dumps.append(output.name)
    return RuntimeDiagnostics(
        destination=destination,
        processes=tuple(processes),
        thread_dumps=tuple(thread_dumps),
    )


def collect_diagnostics(
    *,
    args: argparse.Namespace,
    process: subprocess.Popen[str],
    original_reason: str | None,
    outcome: str | None,
    root_complete: bool,
    baseline: DockerResourceInspection,
    observation: DockerResourceObservation,
    runtime: RuntimeDiagnostics | None = None,
) -> Path:
    runtime = runtime or capture_runtime_diagnostics(args, process)
    manifest = {
        "stage": args.stage,
        "reason": original_reason or outcome,
        "originalReason": original_reason,
        "outcome": outcome,
        "rootSuiteComplete": root_complete,
        "actor": "test-worker" if root_complete else "gradle-stage",
        "pid": process.pid,
        "processes": runtime.processes,
        "disk": disk_snapshot(Path.cwd()),
        "dockerResources": {
            "trusted": baseline.trusted and observation.trusted,
            "baseline": [docker_resource_record(item) for item in baseline.resources],
            "current": [docker_resource_record(item) for item in observation.current],
            "new": [docker_resource_record(item) for item in observation.new],
            "cached": [docker_resource_record(item) for item in observation.cached],
            "removed": [docker_resource_record(item) for item in observation.removed],
            "stateChanged": [
                {
                    "before": docker_resource_record(change.before),
                    "after": docker_resource_record(change.after),
                }
                for change in observation.state_changed
            ],
        },
        "threadDumps": runtime.thread_dumps,
    }
    (runtime.destination / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return runtime.destination


def docker_resource_record(resource: DockerResourceIdentity) -> dict[str, object]:
    return {
        "kind": resource.kind,
        "id": bounded_resource_field(resource.id),
        "name": bounded_resource_field(resource.name),
        "status": bounded_resource_field(resource.status),
        "labels": {
            label: bounded_resource_field(value)
            for label, value in resource.labels
            if label in ALLOWLISTED_DOCKER_LABELS
        },
        "state": bounded_resource_field(resource.state or "none"),
        "health": bounded_resource_field(resource.health or "none"),
    }


def terminate(
    process: subprocess.Popen[str],
    windows_guard: "WindowsProcessTreeGuard | None" = None,
    posix_guard: "PosixProcessGroupGuard | None" = None,
) -> bool:
    if os.name == "nt":
        return terminate_windows_process_tree(process, guard=windows_guard)
    if posix_guard is None:
        posix_guard = preacquire_posix_process_group_guard(process)
    if posix_guard is None:
        inventory = posix_process_inventory(process.pid)
        return inventory is not None and not any(
            item.pgid == process.pid for item in inventory
        )
    members = posix_guard.revalidate()
    if members is None:
        return False
    if not members:
        return True
    for termination_signal in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
        members = posix_guard.revalidate()
        if members is None:
            return False
        if not members:
            return True
        try:
            os.killpg(posix_guard.root.pgid, termination_signal)
        except ProcessLookupError:
            pass
        except OSError:
            return False
        if wait_for_posix_process_group_exit(
            process,
            posix_guard.root.pgid,
            POSIX_TERMINATION_TIMEOUT_SECONDS,
            posix_guard=posix_guard,
        ):
            return True
    return False


def posix_process_inventory(
    process_group_id: int | None = None,
) -> tuple[PosixProcessIdentity, ...] | None:
    result = run_quiet(["ps", "-axo", "pid=,ppid=,pgid=,stat="], timeout=3)
    if result is None or result.returncode != 0:
        return None
    inventory = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) != 4 or not all(part.isdigit() for part in parts[:3]):
            return None
        pid, ppid, pgid = map(int, parts[:3])
        if process_group_id is not None and pgid != process_group_id:
            continue
        inventory.append(
            PosixProcessIdentity(
                pid=pid,
                ppid=ppid,
                pgid=pgid,
                started_at=posix_process_start_identity(pid),
                state=parts[3],
            )
        )
    return tuple(inventory)


def posix_process_start_identity(pid: int) -> str | None:
    if sys.platform.startswith("linux"):
        try:
            stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return None
        command_end = stat.rfind(")")
        fields_from_state = stat[command_end + 2 :].split() if command_end >= 0 else []
        if len(fields_from_state) <= 19 or not fields_from_state[19].isdigit():
            return None
        return f"linux-proc-start-ticks:{fields_from_state[19]}"
    if sys.platform == "darwin":
        return darwin_process_start_identity(pid)
    return None


def darwin_process_start_identity(pid: int) -> str | None:
    try:
        import ctypes
        import ctypes.util

        class ProcBsdInfo(ctypes.Structure):
            _fields_ = [
                ("pbi_flags", ctypes.c_uint32),
                ("pbi_status", ctypes.c_uint32),
                ("pbi_xstatus", ctypes.c_uint32),
                ("pbi_pid", ctypes.c_uint32),
                ("pbi_ppid", ctypes.c_uint32),
                ("pbi_uid", ctypes.c_uint32),
                ("pbi_gid", ctypes.c_uint32),
                ("pbi_ruid", ctypes.c_uint32),
                ("pbi_rgid", ctypes.c_uint32),
                ("pbi_svuid", ctypes.c_uint32),
                ("pbi_svgid", ctypes.c_uint32),
                ("pbi_rfu_1", ctypes.c_uint32),
                ("pbi_comm", ctypes.c_char * 16),
                ("pbi_name", ctypes.c_char * 32),
                ("pbi_nfiles", ctypes.c_uint32),
                ("pbi_pgid", ctypes.c_uint32),
                ("pbi_pjobc", ctypes.c_uint32),
                ("e_tdev", ctypes.c_uint32),
                ("e_tpgid", ctypes.c_uint32),
                ("pbi_nice", ctypes.c_int32),
                ("pbi_start_tvsec", ctypes.c_uint64),
                ("pbi_start_tvusec", ctypes.c_uint64),
            ]

        library_path = ctypes.util.find_library("proc") or "/usr/lib/libproc.dylib"
        library = ctypes.CDLL(library_path, use_errno=True)
        proc_pidinfo = library.proc_pidinfo
        proc_pidinfo.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_uint64,
            ctypes.c_void_p,
            ctypes.c_int,
        ]
        proc_pidinfo.restype = ctypes.c_int
        info = ProcBsdInfo()
        size = ctypes.sizeof(info)
        if proc_pidinfo(pid, 3, 0, ctypes.byref(info), size) != size:
            return None
        return (
            "darwin-proc-start-micros:"
            f"{info.pbi_start_tvsec * 1_000_000 + info.pbi_start_tvusec}"
        )
    except (AttributeError, OSError, ValueError):
        return None


def preacquire_posix_process_group_guard(
    process: subprocess.Popen[str],
) -> PosixProcessGroupGuard | None:
    return preacquire_posix_process_group_guard_state(process).guard


def preacquire_posix_process_group_guard_state(
    process: subprocess.Popen[str],
) -> PosixProcessGroupGuardState:
    if os.name == "nt":
        return PosixProcessGroupGuardState(trusted=False, guard=None)
    initial_identity = posix_process_start_identity(process.pid)
    if initial_identity is not None:
        try:
            process_group_id = os.getpgid(process.pid)
        except (ProcessLookupError, PermissionError, OSError):
            process_group_id = None
        confirmed_identity = posix_process_start_identity(process.pid)
        if (
            process_group_id == process.pid
            and confirmed_identity is not None
            and confirmed_identity == initial_identity
        ):
            return PosixProcessGroupGuardState(
                trusted=True,
                guard=PosixProcessGroupGuard(
                    PosixProcessIdentity(
                        pid=process.pid,
                        ppid=os.getpid(),
                        pgid=process.pid,
                        started_at=initial_identity,
                    )
                ),
            )
    deadline = time.monotonic() + 0.25
    while True:
        inventory = posix_process_inventory(process.pid)
        if inventory is None:
            return PosixProcessGroupGuardState(trusted=False, guard=None)
        root = next((item for item in inventory if item.pid == process.pid), None)
        if root is not None and root.pgid == process.pid and root.started_at is not None:
            return PosixProcessGroupGuardState(
                trusted=True, guard=PosixProcessGroupGuard(root)
            )
        if root is None:
            return PosixProcessGroupGuardState(trusted=True, guard=None)
        if root.pgid != process.pid or time.monotonic() >= deadline:
            return PosixProcessGroupGuardState(trusted=False, guard=None)
        time.sleep(0.01)


def posix_process_group_members(process_group_id: int) -> tuple[int, ...] | None:
    result = run_quiet(["ps", "-axo", "pid=,pgid="], timeout=3)
    if result is None or result.returncode != 0:
        exists = posix_process_group_exists(process_group_id)
        if exists is None:
            return None
        return (process_group_id,) if exists else ()
    members = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) != 2 or not all(part.isdigit() for part in parts):
            return None
        pid, pgid = map(int, parts)
        if pgid == process_group_id:
            members.append(pid)
    return tuple(sorted(members))


def posix_process_group_exists(process_group_id: int) -> bool | None:
    try:
        os.killpg(process_group_id, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return None


def wait_for_posix_process_group_exit(
    process: subprocess.Popen[str],
    process_group_id: int,
    timeout_seconds: float,
    *,
    posix_guard: PosixProcessGroupGuard | None = None,
) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while True:
        process.poll()
        members = (
            posix_guard.revalidate()
            if posix_guard is not None
            else posix_process_group_members(process_group_id)
        )
        if members is None:
            if posix_guard is None and posix_process_group_exists(process_group_id) is False:
                return True
        elif not members:
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(POSIX_PROCESS_POLL_SECONDS, remaining))


def wait_for_tracked_posix_process_group_drain(
    process: subprocess.Popen[str],
    posix_guard: PosixProcessGroupGuard,
    timeout_seconds: float,
) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while True:
        process.poll()
        members = posix_guard.revalidate()
        if members is None:
            return False
        if not members:
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(POSIX_PROCESS_POLL_SECONDS, remaining))


def terminate_windows_process_tree(
    process: subprocess.Popen[str],
    guard: "WindowsProcessTreeGuard | None" = None,
) -> bool:
    initial = windows_process_inventory()
    if guard is None:
        if initial is None:
            return False
        captured = descendant_processes(initial, process.pid)
        if (
            captured is None
            or not any(item.pid == process.pid for item in captured)
            or any(item.creation_date is None for item in captured)
        ):
            return False
        root = next(item for item in captured if item.pid == process.pid)
        guard = create_windows_process_tree_guard()
        if guard is None:
            return False
    else:
        root = guard.owned_with_pid(process.pid)
        captured = ()
        if root is None:
            guard.close()
            return False
    outcome = False
    try:
        if initial is None:
            return False
        if not guard.acquire(root):
            return False
        if captured:
            pending = [item for item in captured if item.pid != root.pid]
            while pending:
                progressed = False
                for item in tuple(pending):
                    parent = guard.owned_with_pid(item.ppid)
                    if parent is not None and guard.acquire(item, parent=parent):
                        pending.remove(item)
                        progressed = True
                if not progressed:
                    return False
        ownership_conflict = guard.discover(initial)

        root_current = process_with_pid(initial, process.pid)
        if root_current is not None and process_incarnation(root_current) != process_incarnation(root):
            return False
        if root_current is not None and process.poll() is None:
            try:
                process.send_signal(signal.CTRL_BREAK_EVENT)
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                pass

            current = windows_process_inventory()
            if current is None:
                return False
            ownership_conflict = guard.discover(current) or ownership_conflict
        else:
            current = initial
        root_current = process_with_pid(current, process.pid)
        if root_current is not None and process_incarnation(root_current) != process_incarnation(root):
            return False
        if root_current is not None:
            if not guard.terminate(root):
                ownership_conflict = True
            current = windows_process_inventory()
            if current is None:
                return False

        quiet_inventories = 0
        stages = 0
        while stages < MAX_WINDOWS_TERMINATION_STAGES:
            ownership_conflict = guard.discover(current) or ownership_conflict
            live_owned = guard.live_owned(current, excluding_pid=process.pid)
            if live_owned:
                target = min(live_owned, key=lambda item: item.pid)
                if not guard.terminate(target):
                    ownership_conflict = True
                quiet_inventories = 0
            else:
                quiet_inventories += 1
                if quiet_inventories >= WINDOWS_QUIET_INVENTORIES:
                    outcome = not ownership_conflict
                    break
            current = windows_process_inventory()
            if current is None:
                return False
            stages += 1
    finally:
        if not guard.close():
            outcome = False
    return outcome


@dataclass
class HeldWindowsProcess:
    identity: ProcessIdentity
    handle: object
    creation_ticks: int


class WindowsProcessTreeGuard:
    def __init__(self, api: object) -> None:
        self._api = api
        self._owned: dict[tuple[int, str | None], HeldWindowsProcess] = {}
        self._trusted = True

    def owned_with_pid(self, pid: int) -> ProcessIdentity | None:
        matches = [held.identity for held in self._owned.values() if held.identity.pid == pid]
        return matches[0] if len(matches) == 1 else None

    def acquire(
        self, identity: ProcessIdentity, parent: ProcessIdentity | None = None
    ) -> bool:
        incarnation = process_incarnation(identity)
        if incarnation in self._owned:
            return True
        if identity.creation_date is None or self.owned_with_pid(identity.pid) is not None:
            return False
        handle = None
        try:
            expected_creation = creation_date_to_filetime_ticks(identity.creation_date)
            if parent is not None:
                parent_held = self._owned.get(process_incarnation(parent))
                if parent_held is None or identity.ppid != parent.pid:
                    return False
                parent_exit = self._api.exit_time_ticks(parent_held.handle)
                if parent_exit is None or expected_creation < parent_held.creation_ticks:
                    return False
                if parent_exit > 0 and expected_creation > parent_exit:
                    return False
            handle = self._api.open_process(identity.pid, WINDOWS_PROCESS_ACCESS)
            if not handle:
                return False
            actual_creation = self._api.creation_time_ticks(handle)
            if actual_creation != expected_creation:
                self._close_unowned(handle)
                handle = None
                return False
            self._owned[incarnation] = HeldWindowsProcess(
                identity=identity, handle=handle, creation_ticks=expected_creation
            )
            handle = None
            return True
        except (AttributeError, OSError, OverflowError, TypeError, ValueError):
            if handle:
                self._close_unowned(handle)
            return False

    def discover(self, inventory: tuple[ProcessIdentity, ...]) -> bool:
        conflict = any(
            self.owned_with_pid(item.pid) is not None
            and process_incarnation(item) not in self._owned
            for item in inventory
        )
        changed = True
        while changed:
            changed = False
            for item in inventory:
                if process_incarnation(item) in self._owned:
                    continue
                parent = self.owned_with_pid(item.ppid)
                if parent is None or item.creation_date is None or parent.creation_date is None:
                    continue
                try:
                    child_created = creation_date_to_filetime_ticks(item.creation_date)
                    parent_held = self._owned[process_incarnation(parent)]
                    parent_exit = self._api.exit_time_ticks(parent_held.handle)
                except (AttributeError, OSError, OverflowError, TypeError, ValueError):
                    conflict = True
                    continue
                if parent_exit is None:
                    conflict = True
                    continue
                if child_created < parent_held.creation_ticks:
                    continue
                if parent_exit > 0 and child_created > parent_exit:
                    continue
                if self.acquire(item, parent=parent):
                    changed = True
                    if len(self._owned) > MAX_CAPTURED_DESCENDANTS + 1:
                        return True
                else:
                    conflict = True
        return conflict

    def live_owned(
        self, inventory: tuple[ProcessIdentity, ...], *, excluding_pid: int
    ) -> list[ProcessIdentity]:
        return [
            item
            for item in inventory
            if item.pid != excluding_pid and process_incarnation(item) in self._owned
        ]

    def terminate(self, identity: ProcessIdentity) -> bool:
        held = self._owned.get(process_incarnation(identity))
        if held is None:
            return False
        try:
            state = self._api.wait_for_single_object(held.handle, 0)
            if state == WAIT_OBJECT_0:
                return True
            if state != WAIT_TIMEOUT or not self._api.terminate_process(held.handle, 1):
                return False
            return (
                self._api.wait_for_single_object(
                    held.handle, WINDOWS_TERMINATION_TIMEOUT_MILLIS
                )
                == WAIT_OBJECT_0
            )
        except (AttributeError, OSError, TypeError, ValueError):
            return False

    def close(self) -> bool:
        succeeded = self._trusted
        for held in reversed(tuple(self._owned.values())):
            try:
                if not self._api.close_handle(held.handle):
                    succeeded = False
            except (AttributeError, OSError, TypeError, ValueError):
                succeeded = False
        self._owned.clear()
        return succeeded

    def _close_unowned(self, handle: object) -> None:
        try:
            if not self._api.close_handle(handle):
                self._trusted = False
        except (AttributeError, OSError, TypeError, ValueError):
            self._trusted = False


def create_windows_process_tree_guard() -> WindowsProcessTreeGuard | None:
    api = load_windows_api()
    return WindowsProcessTreeGuard(api) if api is not None else None


def preacquire_windows_process_tree_guard(
    process: subprocess.Popen[str],
) -> WindowsProcessTreeGuard | None:
    if os.name != "nt":
        return None
    inventory = windows_process_inventory()
    if inventory is None:
        return None
    captured = descendant_processes(inventory, process.pid)
    if captured is None or any(item.creation_date is None for item in captured):
        return None
    root = process_with_pid(captured, process.pid)
    guard = create_windows_process_tree_guard()
    if root is None or guard is None:
        return None
    if not guard.acquire(root) or guard.discover(inventory):
        guard.close()
        return None
    return guard


def terminate_windows_incarnation(process: ProcessIdentity, api: object | None = None) -> bool:
    if process.creation_date is None:
        return False
    try:
        expected_creation = creation_date_to_filetime_ticks(process.creation_date)
    except (OverflowError, ValueError):
        return False
    windows_api = api if api is not None else load_windows_api()
    if windows_api is None:
        return False
    handle = None
    succeeded = False
    try:
        handle = windows_api.open_process(process.pid, WINDOWS_PROCESS_ACCESS)
        if not handle:
            return False
        if windows_api.creation_time_ticks(handle) != expected_creation:
            return False
        if not windows_api.terminate_process(handle, 1):
            return False
        succeeded = (
            windows_api.wait_for_single_object(handle, WINDOWS_TERMINATION_TIMEOUT_MILLIS)
            == WAIT_OBJECT_0
        )
    except (AttributeError, OSError, TypeError, ValueError):
        succeeded = False
    finally:
        if handle:
            try:
                if not windows_api.close_handle(handle):
                    succeeded = False
            except (AttributeError, OSError, TypeError, ValueError):
                succeeded = False
    return succeeded


def load_windows_api() -> object | None:
    try:
        return CtypesWindowsApi()
    except (AttributeError, OSError):
        return None


class CtypesWindowsApi:
    def __init__(self) -> None:
        import ctypes
        from ctypes import wintypes

        class FileTime(ctypes.Structure):
            _fields_ = [("low", wintypes.DWORD), ("high", wintypes.DWORD)]

        self._ctypes = ctypes
        self._file_time = FileTime
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self._kernel32.OpenProcess.restype = wintypes.HANDLE
        self._kernel32.GetProcessTimes.argtypes = [
            wintypes.HANDLE,
            ctypes.POINTER(FileTime),
            ctypes.POINTER(FileTime),
            ctypes.POINTER(FileTime),
            ctypes.POINTER(FileTime),
        ]
        self._kernel32.GetProcessTimes.restype = wintypes.BOOL
        self._kernel32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self._kernel32.TerminateProcess.restype = wintypes.BOOL
        self._kernel32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self._kernel32.WaitForSingleObject.restype = wintypes.DWORD
        self._kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        self._kernel32.CloseHandle.restype = wintypes.BOOL

    def open_process(self, pid: int, access: int) -> object:
        return self._kernel32.OpenProcess(access, False, pid)

    def creation_time_ticks(self, handle: object) -> int | None:
        times = self._process_times_ticks(handle)
        return times[0] if times is not None else None

    def exit_time_ticks(self, handle: object) -> int | None:
        times = self._process_times_ticks(handle)
        return times[1] if times is not None else None

    def _process_times_ticks(self, handle: object) -> tuple[int, int] | None:
        creation = self._file_time()
        exit_time = self._file_time()
        kernel = self._file_time()
        user = self._file_time()
        if not self._kernel32.GetProcessTimes(
            handle,
            self._ctypes.byref(creation),
            self._ctypes.byref(exit_time),
            self._ctypes.byref(kernel),
            self._ctypes.byref(user),
        ):
            return None
        return (
            creation.high << 32 | creation.low,
            exit_time.high << 32 | exit_time.low,
        )

    def terminate_process(self, handle: object, exit_code: int) -> bool:
        return bool(self._kernel32.TerminateProcess(handle, exit_code))

    def wait_for_single_object(self, handle: object, timeout_millis: int) -> int:
        return int(self._kernel32.WaitForSingleObject(handle, timeout_millis))

    def close_handle(self, handle: object) -> bool:
        return bool(self._kernel32.CloseHandle(handle))


def process_incarnation(process: ProcessIdentity) -> tuple[int, str | None]:
    return process.pid, process.creation_date


def process_with_pid(
    inventory: tuple[ProcessIdentity, ...], pid: int
) -> ProcessIdentity | None:
    return next((item for item in inventory if item.pid == pid), None)


def finalize_terminal(
    *,
    args: argparse.Namespace,
    process: subprocess.Popen[str],
    original_reason: str | None,
    requested_exit_code: int,
    root_complete: bool,
    baseline: DockerResourceInspection,
    windows_guard: WindowsProcessTreeGuard | None = None,
    posix_guard: PosixProcessGroupGuard | None | object = POSIX_GUARD_UNSET,
    posix_guard_trusted: bool | None = None,
) -> int:
    runtime = None
    capture_error_type = None
    termination_failed = False
    posix_tracking_enabled = posix_guard is not POSIX_GUARD_UNSET
    effective_posix_guard = posix_guard if isinstance(posix_guard, PosixProcessGroupGuard) else None
    should_cleanup = (
        (os.name == "posix" and posix_tracking_enabled)
        or windows_guard is not None
        or effective_posix_guard is not None
        or process.poll() is None
        or original_reason is not None
    )
    if should_cleanup:
        try:
            if original_reason is not None:
                try:
                    runtime = capture_runtime_diagnostics(args, process)
                except Exception as error:
                    capture_error_type = type(error).__name__
        finally:
            if windows_guard is None:
                if effective_posix_guard is not None and posix_guard_trusted is False:
                    termination_failed = True
                else:
                    termination_failed = (
                        terminate(process, posix_guard=effective_posix_guard)
                        if effective_posix_guard is not None
                        else terminate(process)
                    ) is False
            else:
                termination_failed = terminate(process, windows_guard=windows_guard) is False
    observation = observe_docker_resources(baseline)
    if capture_error_type is not None:
        outcome = "diagnostic-capture-failed"
        exit_code = DIAGNOSTIC_EXIT_CODE
    elif termination_failed:
        outcome = "process-tree-termination-failed"
        exit_code = TERMINATION_EXIT_CODE
    elif not observation.trusted:
        outcome = "docker-resource-inspection-untrusted"
        exit_code = INSPECTION_EXIT_CODE
    elif observation.removed:
        outcome = "removed-baseline-docker-resource"
        exit_code = RESIDUE_EXIT_CODE
    elif observation.state_changed:
        outcome = "docker-container-state-changed"
        exit_code = RESIDUE_EXIT_CODE
    elif observation.new:
        outcome = "new-docker-resource-residue"
        exit_code = RESIDUE_EXIT_CODE
    else:
        outcome = None
        exit_code = requested_exit_code
    if capture_error_type is not None:
        print(
            f"diagnostic outcome={outcome} errorType={capture_error_type}",
            file=sys.stderr,
        )
        return exit_code
    if original_reason is not None or outcome is not None:
        try:
            destination = collect_diagnostics(
                args=args,
                process=process,
                original_reason=original_reason,
                outcome=outcome,
                root_complete=root_complete,
                baseline=baseline,
                observation=observation,
                runtime=runtime,
            )
        except Exception as error:
            print(
                "diagnostic outcome=diagnostic-manifest-write-failed "
                f"errorType={type(error).__name__}",
                file=sys.stderr,
            )
            return DIAGNOSTIC_EXIT_CODE
        print(f"diagnostic captured: {destination}", file=sys.stderr)
    return exit_code


def stream_output(process: subprocess.Popen[str], lines: queue.Queue[str | None]) -> None:
    assert process.stdout is not None
    for line in process.stdout:
        lines.put(line)
    lines.put(None)


def main() -> int:
    args = parse_args()
    baseline = inspect_docker_resources()
    creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    process = subprocess.Popen(
        args.command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=os.name == "posix",
        creationflags=creation_flags,
    )
    windows_guard = preacquire_windows_process_tree_guard(process)
    posix_guard_state = preacquire_posix_process_group_guard_state(process)
    posix_guard = posix_guard_state.guard
    lines: queue.Queue[str | None] = queue.Queue()
    threading.Thread(target=stream_output, args=(process, lines), daemon=True).start()
    started = time.monotonic()
    root_completed_at: float | None = None
    stream_closed = False
    reason: str | None = None
    interrupted = False
    try:
        while process.poll() is None or not stream_closed:
            try:
                line = lines.get(timeout=0.05)
                if line is None:
                    stream_closed = True
                else:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                    if posix_guard is not None:
                        inventory = posix_process_inventory(posix_guard.root.pgid)
                        if inventory is not None:
                            posix_guard.discover(inventory)
                    if (
                        line.rstrip("\r\n") == args.expected_marker
                        and root_completed_at is None
                    ):
                        root_completed_at = time.monotonic()
            except queue.Empty:
                pass
            now = time.monotonic()
            if now - started >= args.timeout_seconds:
                reason = "stage-timeout"
                break
            if (
                root_completed_at is not None
                and now - root_completed_at >= args.post_suite_timeout_seconds
            ):
                reason = "post-suite-timeout"
                break
    except KeyboardInterrupt:
        reason = "interrupted"
        interrupted = True
    if reason is not None:
        requested_exit_code = 130 if interrupted else TIMEOUT_EXIT_CODE
    else:
        requested_exit_code = process.wait()
        if requested_exit_code == 0 and root_completed_at is None:
            reason = "missing-root-suite-marker"
            requested_exit_code = MARKER_EXIT_CODE
        elif requested_exit_code != 0:
            reason = "command-failed"
        elif os.name == "posix" and posix_guard is not None:
            remaining = max(
                0.0,
                args.post_suite_timeout_seconds
                - (time.monotonic() - root_completed_at),
            )
            if not wait_for_tracked_posix_process_group_drain(
                process, posix_guard, remaining
            ):
                reason = "post-suite-timeout"
                requested_exit_code = TIMEOUT_EXIT_CODE
    return finalize_terminal(
        args=args,
        process=process,
        original_reason=reason,
        requested_exit_code=requested_exit_code,
        root_complete=root_completed_at is not None,
        baseline=baseline,
        windows_guard=windows_guard,
        posix_guard=posix_guard,
        posix_guard_trusted=posix_guard_state.trusted,
    )


if __name__ == "__main__":
    raise SystemExit(main())
