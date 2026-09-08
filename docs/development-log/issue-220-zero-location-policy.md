# Issue #220 — 위치 무수집 v2 계약

- 브랜치: `docs/220-zero-location-policy`, base: origin/develop `e677725`.
- 범위: 문서·계약·fixture·정적 검증기. Spring/AI runtime, migration, purge, 배포 없음.
- 현행 정책은 #73의 수집 후 TTL 정책을 명시적으로 대체한다. v1 JSON/fixture는 보존한다.

## RED → GREEN → Refactor

2026-09-08 `python3 -m unittest scripts.tests.test_zero_location_policy`를 먼저 실행하여
새 무수집 검사기 부재로 9개 실패를 확인했다. 이후 v2 계약·검사기·정보 흐름 fixture와
canonical 문서/catalog 링크를 추가해 같은 9개 테스트가 통과했다.

추가 경계는 malformed CLI에서 원문/traceback 미노출과 Shell/PowerShell 모두 현행 검사
실행이며 11개 테스트가 통과했다. REST catalog 검증기도 무수집 v2 링크를 요구한다.
Refactor에서는 정보 흐름 검증과 repository 문서 계보 검증을 나누었다.

검사기는 HTTP body validator가 아니다. 승인 provenance를 client가 선언해도 통과한다는
주장이 없으며 실제 인증/owner/source와 데이터 흐름은 후속 runtime 변경이 검증한다.

## 확인할 품질 게이트

- 전체 저장소 정적 검사 및 `./scripts/quality-gate.sh` (Spring·OpenAPI·Docker 포함)
- 현재 HEAD의 공식 품질 상태 및 독립 Reviewer의 `develop...HEAD` 판정

실행 결과는 공식 품질 artifact와 한국어 Obsidian 일지에 기록한다.
완료되지 않은 runtime 제거를 이 문서의 테스트 통과로 표시하지 않는다.

## 후속 순서와 미완료 Gate

#221 → #222 → #225 → #223 → #224. 기존 domain 계약과 공개 API는 위치 입력 제거 전이며,
이 문서의 정책 적용 원칙과 구현 상태를 구분한다. 모든 표면은 runtimeStatus=pending이다.
TMAP 저장 허용(#216), source 소유권 ADR, 생성 계약(#89), facts/MCP0.8 및 실제 staging은 별도다.
Notion/Figma는 readback 없이 not-linked로 유지한다. #168은 Spring 위치 수집 활성화 권한이 아니다.

## 추가 검토 상태

- 독립 리뷰에서 공개 Places/Weather/FCM 등의 실행 가능한 canonical 요청 계약도
  함께 전환해야 한다는 MAJOR finding을 확인했다. 정책 descriptor만으로 #220을
  완료하거나 PR의 Closes 대상으로 선언하지 않는다.
- Places의 위치 비수집 필터에 `savedOnly`를 보존하는 RED/GREEN 테스트를 추가했다.
  정책 단위 12개 통과. 이전 전체 gate는 리뷰 수정 전 SHA 검증이므로 중단했으며
  통합 완료 근거로 사용하지 않는다.
- 미래 not-ready 계약이 현행 OpenAPI를 깨뜨리는 선행 #226을 별도 branch에서
  구현·검증 중이다. #226 반영 후 최신 develop에서 공개 계약 전환을 재검증한다.

## Places 공개 계약 v2 준비

- RED: canonical Places 요청·cursor와 목록 사용자 거리의 위치 의존 검출.
- GREEN: 위치 query·nearby 정렬·사용자 distanceMeters 제거, savedOnly 및 공개 장소 좌표와
  장소→정류장 거리 유지. closed schema·fixture·validator와 catalog local 2.0.0 정렬.
- 과거 Notion v1.1 필드 readback은 역사로 보존하고 v2 외부 연결로 재사용하지 않음.
- 관련 단위 42개, 저장소 Python 자동화 808개(3 skipped) 통과.
- Weather/실행/live/MCP/FCM 공개 계약 전환과 #226 이후 Spring 재검증이 남아 있다.
  이 커밋만으로 #220 완료 또는 위치 수집 제거 완료를 선언하지 않는다.

## Weather 공개 계약 v2 준비

- RED: 날씨 canonical query가 lat/lng를 필수로 요구하고 v2 selector가 없는 경계 검출.
- GREEN: regionCode/placeId/tripItemId exactly-one + dateTime, planned item의 JWT owner,
  selector 400·미인증 401·참조 은닉 404 계약과 fixture를 정렬했다.
- historical-v1.contract.json에 과거 1.0.0 계약/외부 readback을 원본 그대로 보존했다.
  v2 readiness는 세 단계 모두 not-ready, 외부 버전은 not-linked다.
- RDB/API 문서 요청·응답 version과 validator를 함께 전환했다.
- 관련 단위 84개, 저장소 Python 자동화 809개(3 skipped) 통과.
- 실행/live/MCP/FCM 전환 및 #226 반영 후 Spring 검증은 여전히 미완료다.

## FCM v2와 cursor 경계 보완

- RED: FCM requiredSignals에 위치 동의가 남고 Places cursor가 기존 GPS scope를 재사용하는 경계 검출.
- GREEN: FCM은 active device/OS granted/server opt-in의 8개 조합과 잘못된 증거 5개를
  실행한다. 위치 동의 snapshot·취소 reason·target 호출 전 조건을 제거했다.
- FCM v1 원본을 historical-v1.contract.json으로 보존하고 canonical digest를 검증한다.
  v1 issue readback은 v2 승인으로 재사용하지 않으며 contractReady=false를 유지한다.
- Places는 plc2. cursor 형식과 별도 서명 key domain을 요구한다. 클라이언트는 과거 token을
  전송 전에 폐기하고 서버는 legacy payload를 decode하기 전에 거부해야 한다.
- Weather의 지원 범위 오류를 공개/계획 selector로 정정했다. 실제 runtime 변경은 아니다.
- 관련 테스트 56개, Python 전체 811개(3 skipped) 통과. 독립 reviewer의 이 변경 범위
  advisory에서 추가 finding 없음. #220 전체 승인 또는 전체 gate 완료를 의미하지 않는다.
- 실행/live/MCP 계약과 선행 #226 반영 후 Spring 전체 검증은 남아 있다.

## 진행 요청 문서의 v2 예시 정렬

- RED: 실행·빈 시간·복구·라이브 요청의 옛 위치/alias 예시가 v2 정책 필드와 불일치함을 검출.
- GREEN: 네 POST 예시를 계획 item/leg, 시간과 수동 진행으로 정렬했다. 각각 runtime
  not-ready이며 상세 owner 계약/구현 검증은 후속임을 표시했다.
- validator는 실제 Markdown 요청 예시를 읽어 닫힌 정책 필드와 문자열 값을 검사한다.
  GPS key 및 계획 ID 내부의 nested 위치 재도입을 거부한다. HTTP validator는 아니다.
- 정책 단위 18개, Python 전체 813개(3 skipped) 통과.
- #226은 PR #227로 생성됐으며 merge 후 최신 develop에서 #220 전체 gate를 수행한다.

## 선행 PR #227 반영 후 통합 회귀

- develop b0c490f8a6ea1b2d3beb8e400a179055f3099bbe를 merge했다.
- RED: 공통 테스트에서 Weather가 예전 ready라고 가정해 실패했다. v2의 not-ready로 보정했다.
- Places OpenAPI는 미래 v2를 테스트에서 ready로 강제하지 않고 실제 runtime을 검사한다.
  기존 runtime의 double 좌표 경계와 String path ID 문서 표기를 그대로 검증한다.
- GREEN: Python readiness 5개와 Places/SavedPlaces/FrontendOpenApi 관련 slice 통과.
- 독립 검토의 계약 미해결 finding은 0건이다. 최종 동일 SHA 전체 gate와 승인은 별도로 기록한다.
