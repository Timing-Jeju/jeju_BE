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
