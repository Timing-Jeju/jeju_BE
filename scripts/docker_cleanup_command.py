"""Unix smoke 정리 명령을 제한하고 소유 프로세스 그룹의 종료를 검증한다."""

import os
import signal
import subprocess
import sys
import tempfile
import time

if os.name == "posix":
    import resource

from gradle_stage_watchdog import (
    posix_process_group_members,
    posix_process_inventory,
    preacquire_posix_process_group_guard_state,
    terminate,
    wait_for_posix_process_group_exit,
)


def limit_output_file():
    """정리 명령의 임시 출력 파일에 커널 수준의 크기 상한을 적용한다."""
    resource.setrlimit(resource.RLIMIT_FSIZE, (65536, 65536))


def live_worker_group_members(group_id):
    """그룹 리더가 살아 있는 동안 같은 세션의 남은 명령만 식별한다."""
    inventory = posix_process_inventory(group_id)
    if inventory is None:
        return None
    return tuple(
        item.pid for item in inventory
        if item.pgid == group_id
        and item.pid != os.getpid()
        and item.ppid != os.getpid()  # inventory를 조회하는 ps 자신은 즉시 종료한다.
        and not item.state.startswith("Z")
    )


def run_group_worker(command):
    """명령 부모가 먼저 끝나도 그룹 리더를 유지해 자식 회수 권한을 보존한다."""
    try:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        code = child.wait()
    except OSError:
        return 1
    group_id = os.getpgrp()
    while True:
        members = live_worker_group_members(group_id)
        if members is not None:
            break
        time.sleep(0.02)
    if not members:
        return code

    signal.signal(signal.SIGTERM, lambda _signum, _frame: None)
    try:
        os.killpg(group_id, signal.SIGTERM)
    except OSError:
        return 1
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        members = live_worker_group_members(group_id)
        if members == ():
            return 1
        time.sleep(0.02)
    # SIGKILL includes this worker, but leaves no observed process behind.
    os.killpg(group_id, signal.SIGKILL)
    return 1


def run(command, *, timeout=30):
    """124는 제한 시간 초과 후 소유 프로세스 회수가 입증된 경우에만 반환한다."""
    if os.name != "posix" or not command or not 0 < timeout <= 30:
        return 1, b""
    with tempfile.TemporaryFile() as output:
        try:
            process = subprocess.Popen(
                [sys.executable, os.path.abspath(__file__), "--group-worker", *command],
                stdin=subprocess.DEVNULL, stdout=output,
                stderr=subprocess.DEVNULL, start_new_session=True,
                preexec_fn=limit_output_file,
            )
        except OSError:
            return 1, b""
        deadline = time.monotonic() + timeout
        state = preacquire_posix_process_group_guard_state(process)
        timed_out = False
        oversized = False
        while process.poll() is None:
            if state.guard is not None:
                state.guard.revalidate()
            oversized = os.fstat(output.fileno()).st_size >= 65536
            timed_out = time.monotonic() >= deadline
            if oversized or timed_out:
                break
            time.sleep(0.02)
        if process.poll() is None:
            if not state.trusted or not terminate(process, posix_guard=state.guard):
                return 1, b""
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                return 1, b""
        if not state.trusted:
            return 1, b""
        drained = (
            wait_for_posix_process_group_exit(process, process.pid, 3, posix_guard=state.guard)
            if state.guard is not None else posix_process_group_members(process.pid) == ()
        )
        if not drained:
            terminate(process, posix_guard=state.guard)
            return 1, b""
        if oversized or os.fstat(output.fileno()).st_size >= 65536:
            return 1, b""
        if timed_out:
            return 124, b""
        if process.returncode != 0:
            return 1, b""
        output.seek(0)
        return 0, output.read()


if __name__ == "__main__":
    if sys.argv[1:2] == ["--group-worker"]:
        raise SystemExit(run_group_worker(sys.argv[2:]))
    status, output = run(sys.argv[1:])
    sys.stdout.buffer.write(output)
    raise SystemExit(status)
