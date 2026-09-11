# Issue #242 RLS event-trigger 실행 권한 경계 개발 기록

## 범위

- 기준: `origin/develop` `06785b9a4e2350b3b1a07d0089cd48cbf70a1747` 및 의존 #223 `7473dd6fd4d79540b0f35f117514f151bbbb57db`
- 브랜치: `fix/242-rls-event-trigger-execute-boundary`
- canonical suffix에 append-only 060 migration을 추가해 `public.rls_auto_enable()`의
  PUBLIC·anon·authenticated EXECUTE를 회수한다.
- 함수 본문과 event trigger는 변경하지 않고 신규 public table의 RLS 자동 활성화를 보존한다.
- `profile-images` bucket/RLS를 포함한 전체 canonical replay와 PostgreSQL 17 검증을 완료하기 전에는
  배포 준비 완료로 판단하지 않는다. live Supabase 적용은 수행하지 않는다.

## Red

운영 SQL보다 먼저 `scripts/tests/test_rls_auto_enable_security_migration.py`를 추가하고 다음을 고정했다.

- 당시 055 migration의 append-only 경로와 정확한 세 역할 권한 회수
- 함수/event trigger의 create·replace·drop 금지
- manifest slot, owner Issue, SHA-256, 직전 migration dependency
- compose 3종 및 shell replay 순서
- Supabase 사전 설치 event-trigger 경계의 Docker 호환 fixture
- PG16/17 actual catalog에서 anon/authenticated 거부와 신규 table RLS 활성화
- 적용 전후 점검, project ref 재확인, profile-images, rollback release runbook

최초 `python3 -m unittest scripts.tests.test_rls_auto_enable_security_migration`은 4개 테스트에서
`FAILED (failures=5, errors=2)`였다. 055 migration 파일 부재, manifest 마지막 항목이 054인 상태,
compose와 smoke replay에 055가 없다는 의도한 이유였다. release runbook 테스트도 문서 부재로 별도
Red를 확인했다.

## Green과 보안 판단

최종 `20260918000022_rls_auto_enable_execute_boundary.sql`은 기존 함수를 전제로 plain `REVOKE`만 수행한다.
지원 환경은 실제 Supabase의 사전 설치 함수 또는 일반 PostgreSQL QA의 명시적 `auth_compat.sql`
fixture다. 함수가 없으면 조용히 통과시키지 않고 migration을 실패시켜 event-trigger가 사라진
bootstrap을 숨기지 않는다.

Event trigger는 함수 소유자 권한으로 실행되므로 `service_role` 직접 EXECUTE grant는 필요하지 않다.
PUBLIC grant 회수 후 postgres/migration owner의 implicit 권한과 event-trigger 호출만 유지한다.
actual PostgreSQL 테스트는 함수가 SECURITY DEFINER인지, event trigger가 enabled인지, 신규 public
probe table이 자동으로 `relrowsecurity=true`가 되는지를 PG16/17에서 함께 검증한다.

focused Green 결과는 다음과 같다.

- 보안 migration 계약: 8건 성공
- canonical migration order/manifest 계약: 14건 성공
- 기존 profile-images migration 계약: 8건 성공
- `git diff --check`: 성공

## Refactor와 검증 상태

권한 회수 migration에는 조건문, 함수 재정의, event trigger 변경을 넣지 않았다. Docker 호환 객체는
`db/local-postgres`에만 격리했고 canonical migration은 실제 Supabase 객체를 수정하지 않는다.
운영 적용 절차는 `docs/SUPABASE_BOOTSTRAP_RELEASE.md`에 분리해 정확한 project ref 재확인, 읽기 전용
preflight, transaction probe, 전체 public table RLS, profile-images 정책, forward-correction 우선
rollback 원칙을 기록했다.

전체 quality gate와 disposable Docker/PostgreSQL 17 replay 결과는 실행 후 이 기록에 추가한다.

## Reviewer 보정과 actual PG16/17 재검증

첫 actual focused 실행은 Docker fixture의 PL/pgSQL record 변수와 SQL qualifier가 충돌해
`record "command" is not assigned yet`로 실패했다. 별도 `ddl_command` alias를 요구하는 정적
테스트를 먼저 추가해 Red를 재현하고 fixture를 보정했다.

Reviewer는 첫 Green 뒤 세 가지 검증 공백을 확인했다.

1. 신규 canonical loop가 image 문자열을 순회하면서도 default `create()`만 호출해 PG17을 실제로
   선택하지 않았다.
2. profile-images PG16/17 테스트가 044만 적용해 당시 045~055 전체 canonical replay 뒤의 정책을
   검증하지 않았다.
3. release runbook에 remote dry-run pending 목록과 manifest exact order 대조가 없었다.

세 계약을 먼저 정적 테스트로 강화하자 정확히 3건이 실패했다. Green에서는 canonical loop를
`createBefore(FIRST_SUFFIX, image)`와 전체 suffix replay로 바꾸고 각 컨테이너의
`server_version_num` major를 16/17로 확인했다. Storage loop는 044 직전에 호환 schema를 만들고
044부터 당시 마지막 migration까지 같은 DB에 적용한 뒤 public bucket, owner insert, wrong-owner/invalid-key 거부,
UPDATE/DELETE 거부, anon INSERT/SELECT 거부를 실행한다. Runbook은 Reviewer 승인과 project ref
재확인 뒤 `supabase db push --dry-run` pending 목록을 manifest exact ordered list와 대조하며 mismatch면
실제 push를 중단하도록 보강했다.

보정 후 focused Python 31건, Spotless, `compileTestJava`가 성공했다. 실제
`CanonicalMigrationOrderIntegrationTest`와 `ProfileImageStoragePolicyMigrationIntegrationTest`의
PG16/PG17 실행은 15분 14초에 성공했고 Testcontainers session의 container/network/volume은 모두
0으로 정리됐다. live Supabase는 적용하지 않았다.

## 공식 gate의 develop 의존성과 초기 055 expectation 보정

정확한 HEAD `64e31a14dbae1dea0cf7165d2817489f7cfa1a19`에서 공식 quality gate를
1회 실행했다. 저장소 자동화 Python 단계에서 830건 중 8건이 실패해 Docker 단계 전 종료됐다.
이 중 2건은 신규 055를 반영하지 않은 migration chronology와 Docker init ordering expectation이었다.
공식 실패를 Red로 삼아 expected migration tuple, compose mount order, smoke concurrency sequence를
055까지 확장했고 targeted 2건과 관련 보안 Python 31건이 Green으로 돌아왔다.

나머지 6건은 `test_openapi_integration_once.py`의 fake root/worker 종료 테스트가 기대한
0·124·125 대신 모두 129를 반환한 동일 watchdog 경계 실패다. Open PR #240의 head
`fix/235-linux-ci-watchdog-collector-drain-race`에는 이 파일의 후속 보정 commit
`3dc4f3618a62722050beeb06eb3725778d5325e3`이 있으나 현재 `develop`에는 포함되지 않았다.
#242 범위에서는 OpenAPI harness/watchdog 파일을 수정하지 않고 #240 merge 뒤 최신 develop 통합을
기다린다. 따라서 이 시점의 공식 gate는 성공이 아니며 Docker smoke도 실행하지 않았다.

## 최신 canonical chain 재통합

`origin/develop` `06785b9a`와 위치 제거 의존선 #223 exact HEAD `7473dd6f`를 기존 #242 커밋 위에
병합했다. 새 canonical suffix `20260918000017`~`20260918000021`과 Docker init `055`~`059`는
수정하지 않고, 권한 회수 migration을 최종 `20260918000022` / init `060`으로 이동했다. manifest
dependency는 `20260918000021_day_activity_window_pair.sql`을 정확히 가리킨다. compose 3종, Docker
smoke replay, canonical order, PG16/17 full replay, profile-images LAST boundary도 모두 060으로 맞춘다.
공유 Docker 검증이 진행 중인 동안에는 Testcontainers와 전체 gate를 실행하거나 정리하지 않는다.
