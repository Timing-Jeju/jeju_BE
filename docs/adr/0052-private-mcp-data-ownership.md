# ADR-0052: planner 외부 데이터 소유권을 private MCP로 이동

- 상태: Accepted for Issue #52 implementation
- 날짜: 2026-09-01
- 계약: jeju_AI `0.7.0`

## 맥락

기존 #52 선행조건은 BE가 TMAP/TAGO 원본을 수집·보관한 뒤 AI에 계산용 fact를 보내는 구조를 전제로 #31(외부 데이터 health)과 #62(snapshot retention)를 요구했다. 현재 승인된 planner 계약은 다르다. `jeju_AI`가 `config/data_sources.toml`에 승인된 소스만 호출하고 Pydantic 계약으로 축소한 evidence만 반환한다. BE는 원본 provider body나 geometry를 받지 않는다.

## 결정

- planner 경로에서 TourAPI/TAGO/TMAP 호출과 source allowlist는 private MCP가 소유한다.
- BE는 사용자 소유권, canonical place crosswalk, 일정 command와 적용 transaction을 소유한다.
- BE→MCP 전송은 Pydantic v0.7 입력의 structured field로 제한한다.
- MCP→BE 결과는 `structuredContent`만 사용하고 `tools/list` schema checksum, JSON Schema, ID allowlist를 통과해야 한다.
- `commandInputHash`와 실제 MCP arguments의 `mcpInputHash`는 별도로 기록한다.
- 감사 로그는 hash/count/status/latency만 저장한다. JWT, 사용자 원문, provider payload, TMAP geometry는 저장하지 않는다.
- #31과 #62는 BE 자체 외부 데이터 기능의 이슈로 유지하며 완료 처리하지 않는다. planner #52의 선행조건에서는 제거한다.
- 기존 BE route provider(#41)는 별도 BE 기능으로 유지하고 MCP planner 경로의 증거로 재사용하지 않는다.

## 결과

private MCP 장애는 planner run만 실패시키고 active schedule을 변경하지 않는다. schema drift는 애플리케이션 초기화 단계에서 닫힌 실패가 된다. TAGO 실시간 fact가 없는 경우 live 성공을 만들지 않고 `realtime_window_deferred` 또는 대응하는 구조화된 outcome을 유지한다.
