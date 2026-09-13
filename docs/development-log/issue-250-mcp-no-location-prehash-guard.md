# Issue #250 MCP 무위치 pre-hash guard 보완 — 2026-09-11

최신 `origin/develop`의 `dbae76541bfd1329f476d9372842716ad8bb5aeb`에서
`fix/250-no-location-prehash-guard` 브랜치를 분리했다. #223 migration·fixture와 Trip/Schedule/Day
운영 경로는 변경하지 않고, 이미 hash 전에 실행되는 MCP 무위치 guard의 재귀 alias 차단만 보완했다.

## Red → Green

- Red 명령: `./gradlew test --tests com.timingjeju.api.global.mcp.McpWireNoLocationPreHashTest --no-daemon`
- Red 결과: 45개 중 19개 실패. `gps`, coordinate/telemetry, `lat/lng/lon`,
  latitude/longitude, accuracy/altitude/heading/bearing/speed/velocity/course와 정규화된
  `GRID_100M` type 변형이 guard를 통과해 MCP 호출 경로에 도달했다. 기존 geohash,
  grid-x/grid-y/coarse-location 차단은 유지됐다.
- Green 명령: Red와 동일.
- Green 결과: 45개 전부 통과, `BUILD SUCCESSFUL`. 모든 신규 alias는 중첩 객체·배열에서도
  schema fingerprint/hash, SDK wire, audit 전에 고정 오류 `MCP_USER_LOCATION_FORBIDDEN`으로
  종료하며 원시 합성 좌표를 오류에 반사하지 않는다.

## 구현과 경계

- field key와 `GRID_100M` type value는 ASCII 영숫자만 남기고 소문자화한 뒤 정확한 key
  집합과 비교한다. substring 판정은 사용하지 않는다.
- 명시 선택 `regionCode/placeId/stopId`, `plannedPlaceId/plannedStopId`, 계획 item/leg anchor와
  `travelSpeedAssumption` 같은 업무 필드는 허용한다.
- 과거 0.7 fixture의 공개 좌표를 정상 무위치 입력으로 간주하지 않고, 정상 hash/wire 회귀는
  위치 없는 계획 anchor fixture로 검증한다. 역사 fixture 파일 자체는 변경하지 않았다.

## 남은 완료 조건

최종 집중 검증은 `global.mcp` 단위 테스트 67개(실패/오류/skip 0), `compileJava`,
`compileTestJava`, `spotlessCheck`, `git diff --check` 통과다.

이번 위임 범위는 집중 단위·정적·포맷·diff 검사와 정상 커밋까지다. #223의 위치 purge/write
guard가 통합된 최신 develop에서 전체 `quality-gate.sh`, Docker smoke test, 독립 Reviewer 검토를
완료하기 전에는 `READY_FOR_REVIEW`가 아니다. 운영 DB 적용·배포, PR, push, 승인 상태 파일
조작은 수행하지 않는다.
