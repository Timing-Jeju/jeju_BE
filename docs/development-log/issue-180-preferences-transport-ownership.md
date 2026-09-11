# Issue #180 여행 선호·교통·장소 선호 구현 소유권 계약 정렬

## 범위와 기준

- 최초 TDD 기준: `origin/develop` `ae3926ac6428c1d93cd57372fbafb8dd31d34544`
- 통합 기준: `837a70946390f6d3eebba56a952989db1ed2b7b6`
- 통합 브랜치: `docs/180-preferences-transport-ownership-integration`
- 포함: #86 canonical JSON·Markdown, REST catalog, validator, ownership digest fixture, 정적 테스트
- 제외: Controller, Service, Repository, migration, endpoint wire schema·status·Problem Details 변경

## Red → Green → Refactor

### Red

- 운영 계약보다 endpoint→단일 구현 Issue, `[46,47,48]` coverage, catalog owner와 digest fixture 검사를 먼저 추가했다.
- 집중 테스트에서 9 failures와 1 error를 재현했다.
- 원인은 `implementationIssues=[46,47]`, place-preferences의 `dbOwner=#46`, catalog 네 endpoint의 공통 `#46/#47` 표기, ownership fixture 부재와 validator의 owner drift 미탐지였다.

### Green

- preferences는 #46, transport-event PUT/DELETE는 #47, place-preferences는 #48로 단일 owner를 고정했다.
- validator가 top-level 구현 Issue exact 목록, endpoint당 owner 하나, 알려진 Issue 집합, endpoint exact mapping과 coverage를 검사한다.
- catalog의 owner와 dbOwner도 endpoint별 단일 Issue로 정렬했다.
- ownership projection과 readiness를 canonical JSON으로 직렬화한 SHA-256 fixture를 추가하고 validator가 전체 내용을 재계산해 비교한다.
- 구현 owner와 schema gap 메타데이터를 제외한 wire contract digest는 기존 `develop`과 같은 `d1cd2bd461cbd2e6dc9cda4b43dc66cf4b28fe2d423095c436b26ec606c40214`로 고정했다.

### Refactor

- owner 해석과 projection 생성을 validator helper로 분리했다.
- endpoint 정렬 기준을 path·method로 고정해 파일 순서 변경이 digest를 흔들지 않게 했다.
- DELETE query selector를 별도 endpoint owner로 세지 않고 #47에 포함했다.

## 보존한 계약

- request/response schema, validation, status, Problem Details와 data lineage는 변경하지 않았다.
- metadata/example/implementation readiness는 모두 `not-ready`, evidence는 `null`을 유지했다.
- migration과 운영 코드는 변경하지 않았다.
- 실제 JWT, PII, provider token, 외부 raw payload를 fixture나 로그에 추가하지 않았다.

## 검증

- 통합 전 기준 브랜치 집중 계약 테스트: 16 tests 성공
- 통합 후 전용 owner suite: 20 tests 성공
- preferences-transport와 REST readiness 집중 suite: 72 tests 성공
- 전용 validator: 성공
- JSON 정적 파싱, owner/readiness projection과 wire digest 비교: 성공
- 가용 디스크가 3 GiB 미만이므로 이 통합 단계에서는 Gradle, 전체 품질 게이트와 Docker smoke를 실행하지 않는다.
- 전체 품질 게이트와 Docker 결과는 최종 HEAD 확정 후 별도 검증한다.

## 남은 절차

- 독립 Reviewer 승인 전이다.
- Developer는 PR을 만들지 않는다.
