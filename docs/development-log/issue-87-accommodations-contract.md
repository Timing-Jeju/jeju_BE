# Issue #87 복수 숙소 CRUD API 계약 개발 기록

## 범위와 기준

- 기준: `origin/develop` `0dc4e6bf70e3d947e8dbcc416393317c845849bb`
- 브랜치: `docs/87-c06-api-contract`
- 포함: accommodations POST/PATCH/DELETE 계약, fixture, validator, catalog, 설계 문서와 Notion 세 행
- 제외 확인: Spring Controller/Service/Repository, schema/migration/Flyway, FastAPI 소스는 변경하지 않았다.

## Red → Green

운영 계약보다 `scripts/tests/test_accommodations_contract.py`와 통합 suite 기대값을 먼저 추가했다.

```text
python3 -m unittest scripts.tests.test_accommodations_contract scripts.tests.test_contract_suite_integration -v
```

최초 실행은 `scripts/validate_accommodations_contract.py`가 없어 `FileNotFoundError`, exit 1이었다. XOR·시간대·기간·복수 순서, POST 멱등성, PATCH omitted/null, 삭제/active 일정, owner/404/동시성/Problem과 strict schema·재귀 fixture·mutation 검사 대상이 아직 구현되지 않은 의도한 Red였다.

Green에서는 canonical JSON/Markdown, request/success/problem 합성 fixture, 전용 fail-closed validator, catalog endpoint 3건과 shell/PowerShell gate를 추가했다. REST 공통 validator와 전용 validator, 관련 테스트 12건이 성공했고 scripts 전체 테스트와 py_compile, 비밀정보, diff 검사도 성공했다.

## 확정한 경계

- POST는 `placeId/customName` exact XOR, 6개 property와 `Idempotency-Key`, 강한 `If-Match`를 요구한다.
- Idempotency scope는 canonical sub + method + path + tripId, TTL 24시간이며 replay/payload conflict/동시 요청 의미를 #72 exact 구조로 상속한다.
- PATCH omitted는 유지, null은 반대 identity를 같은 요청에서 설정할 때 losing identity를 지우는 용도로만 허용하며 결과 XOR를 재검증한다.
- 날짜는 `[checkInDate,checkOutDate)`, 시간은 `Asia/Seoul` local `HH:mm`이다. 각 구간은 여행 범위 안에 있고 저장된 숙소 사이는 내부 gap/overlap 없이 인접한다.
- 첫·마지막 edge는 draft 입력 중 비어 있을 수 있어 숙소를 순차 추가할 수 있고, 전체 숙박 coverage는 일정 생성 시 재검증한다.
- client는 sequence를 보내지 않고 서버가 날짜·UUID로 정렬해 같은 transaction에서 `1..N`으로 재번호한다.
- deterministic gap/overlap은 422, 사전 검증 뒤 concurrent exclusion/sequence 충돌은 409다.
- POST/PATCH 실제 변경은 active 일정을 원자적으로 무효화한다. DELETE는 active 일정 또는 중간 gap이면 422이고 성공은 body 없는 204다.
- 타 owner와 다른 여행의 숙소는 canonical sub 기준 404로 숨긴다.

## 외부 추적성과 발견 사항

Notion 기존 세 page ID를 유지하면서 속성과 본문을 `1.0.0`, `Implementation Ready`, exact header/body/status/error matrix에 맞췄고 SQL 및 페이지 재조회로 확인했다.

Figma file `4mKep38zm17iupVSQVsSJW`, page `251:4347`의 `329:5165`, `182:3248`, `653:11512`에서 숙소/복귀 위치 action을 확인했다. 복수 CRUD loading/empty/error와 API contractVersion 연결은 없으므로 `figma=not-linked`, readiness는 모두 `not-ready`로 유지했다.

현재 DB CHECK는 `place_id/custom_name` 둘 다 non-null을 허용하고 exclusion은 overlap만 막는다. XOR migration과 gap/sequence/active 삭제 transaction은 #68의 명시적 후속 범위다. 운영 migration 기준은 계속 `supabase/migrations`이며 Flyway는 도입하지 않는다.

## 검증 대기

Spring clean check, 전체 품질 게이트와 Docker smoke는 다른 직렬 검증 작업이 끝난 뒤 최신 HEAD에서 실행한다. 해당 결과가 모두 성공하기 전에는 READY_FOR_REVIEW를 선언하지 않는다.
