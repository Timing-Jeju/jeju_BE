# Issue #242 RLS event-trigger 실행 권한 경계 개발 기록

## 범위

- 기준: 최신 `origin/develop` `ec2225d3ca5324b45a41860eb4232ed9dcd5d72b`
- 브랜치: `fix/242-rls-event-trigger-execute-boundary`
- canonical suffix에 append-only 055 migration을 추가해 `public.rls_auto_enable()`의
  PUBLIC·anon·authenticated EXECUTE를 회수한다.
- 함수 본문과 event trigger는 변경하지 않고 신규 public table의 RLS 자동 활성화를 보존한다.
- `profile-images` bucket/RLS를 포함한 전체 canonical replay와 PostgreSQL 17 검증을 완료하기 전에는
  배포 준비 완료로 판단하지 않는다. live Supabase 적용은 수행하지 않는다.

## Red

운영 SQL보다 먼저 `scripts/tests/test_rls_auto_enable_security_migration.py`를 추가하고 다음을 고정했다.

- 055 migration의 append-only 경로와 정확한 세 역할 권한 회수
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

`20260918000017_rls_auto_enable_execute_boundary.sql`은 기존 함수를 전제로 plain `REVOKE`만 수행한다.
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
