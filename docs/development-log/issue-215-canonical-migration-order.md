# Issue #215 canonical migration 순서 안전화

## 배경

`origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56` 뒤의 누적 브랜치는 PR 병합 순서와 migration timestamp가 역전되어 있었고, #50과 #51이 같은 `20260907000001` 경로를 서로 다른 blob으로 사용했다. 이 상태에서는 먼저 기록된 migration 파일이 후속 병합에서 바뀔 수 있다.

## Red

운영 파일을 바꾸기 전에 `scripts/tests/test_canonical_migration_order.py` 5개 테스트를 추가했다. `python3 -m unittest scripts.tests.test_canonical_migration_order -v` 결과는 `FAILED (failures=4, errors=1)`이었다. canonical suffix 12개, #50 baseline 경로, manifest, compose/init 정렬, actual PostgreSQL 검증 소스가 모두 없다는 이유로 실패했다.

## Green과 Refactor

- `origin/develop`의 35개 migration과 마지막 `20260907000000`의 SHA-256을 manifest에 동결했다.
- 후속 migration을 `20260918000000..011`, Docker init `038..049`로 재배치했다. seed는 `099`를 유지했다.
- #50 `20260918000007`은 commit `0d275a28`의 blob `dbd52ac6`과 byte-for-byte 동일한 baseline이다.
- #51 `20260918000008`은 강화된 legacy preflight, CHECK, trigger/function/assertion과 정확한 ACL만 교체한다. #50의 composite FK/index와 sealing helper rename은 반복하지 않는다.
- 세 compose, POSIX shell과 PowerShell smoke의 fresh/origin-develop upgrade/#50→#51/concurrency 경로를 manifest 순서에 맞췄다.
- 실제 disposable PostgreSQL을 위한 Testcontainers 소스와 schema·ACL fingerprint SQL을 추가했다.

Supabase CLI는 로컬에 없어 `supabase --help`가 `command not found`로 끝났다. 공식 문서상 remote history는 timestamp를 기준으로 비교하고 적용된 migration을 건너뛰므로, 동일 경로 overwrite 대신 고유 additive timestamp를 사용했다.

## 실행 제한

요청 범위에 따라 actual PostgreSQL/Testcontainers, Docker, live Supabase, 전체 quality gate와 `clean check`는 실행하지 않는다. deterministic Python/static 검사와 Gradle unit/architecture/format/compile만 실행한다.

## 검증 결과

- canonical migration 계약: 6건 성공
- 관련 Python 계약: 70건 성공
- 전체 Python 회귀: 779건 성공, 환경 조건 skip 3건
- Gradle `unitTest`: 1,372건 성공, 환경 조건 skip 9건
- Gradle `architectureTest`: 46건 성공
- 변경된 migration Java 계약: 16건 성공
- `spotlessCheck`, shell syntax, manifest JSON, deploy SQL 정책, REST/domain 정적 validator, diff whitespace, 전체 파일 비밀정보 검사 성공

로컬에는 `supabase` CLI와 `pwsh`가 없어 각각 실제 CLI 검증과 PowerShell parser 검증은 실행하지 못했다. actual PostgreSQL/Testcontainers, Docker smoke, 전체 quality gate, `clean check`는 Issue #215의 명시적 실행 제한에 따라 후속 독립 검토의 환경 gate로 남긴다.
