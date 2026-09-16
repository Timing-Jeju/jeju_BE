# Issue 235 Linux CI watchdog collector drain 경합

## 배경

PR #234의 GitHub Actions common 검사에서 정상 root marker가 출력된 뒤 observed collector가
자연 종료하는 경계가 exit 124와 timeout diagnostic으로 잘못 판정됐다. Linux에서 `ps`가
collector를 관찰한 직후 `/proc/<pid>/stat`을 읽기 전에 프로세스가 종료되면 identity snapshot이
일시적으로 untrusted가 될 수 있다.

## TDD

- RED: `guard.revalidate()`가 transient `None` 뒤 empty를 반환하는 결정적 테스트가 기존
  구현에서 즉시 `False`가 되어 실패했다. 명령과 실패는 Issue #235 댓글에 기록했다.
- GREEN: 이미 소유권을 관찰한 descendant가 있는 경우에만 transient identity snapshot을
  기존 post-suite deadline 안에서 재검증한다. empty snapshot만 성공으로 판정한다.
- fail-closed: root만 관찰한 guard의 untrusted snapshot은 즉시 실패하고, observed collector의
  identity가 계속 불명확하거나 process가 실제로 남으면 deadline에서 실패한다.
- signal/PID 안전: drain은 signal을 보내지 않으며, 기존 PID incarnation 재검증과
  SIGINT → TERM → KILL 종료 경계는 변경하지 않았다.

## 검증 현황

- ownership, transient/persistent identity, PID reuse, signal 경계 7개를 50회 반복해
  350/350 Green을 확인했다.
- 샌드박스가 `ps`를 차단한 실행은 guard가 inventory를 신뢰하지 않고 exit 129로
  fail-closed했다. process inventory 권한을 부여한 단독 전체 suite는 87개 Green,
  Linux 전용 observed collector E2E 본문은 POSIX 호스트에서 20회 반복 Green이었다.
- 정상 commit hook의 Spring unitTest가 통과했고, clean HEAD의 CI common에서 hooks 36개,
  git-hooks 7개, scripts 823개(3 skip)가 모두 Green이었다.
- Docker/full gate/push/PR, 실제 DB, live Supabase, 배포는 수행하지 않았다.
