# Issue #40 TMAP 제주 이동수단 경로 PoC 최종 판정

## 상태

- 최종 판정: `DEFER`
- 라이브 10구간 × 3모드 실행: `SKIPPED`
- 사유: 현재 프로세스에 승인된 TMAP 키가 없고, `jeju_AI` 승인 계약에는 TMAP 대중교통이 없다.
- Architecture Owner 승인: `APPROVED`
- Product Owner 승인: `APPROVED`
- 승인자: `kwongwangjae`
- 승인시각: `2026-09-01T22:41:52Z`
- 승인근거: <https://github.com/Timing-Jeju/jeju_BE/issues/40#issuecomment-5501405118>

두 Owner 승인은 #40의 `DEFER` 판정과 #41 경계를 확정한다. 독립 Reviewer 승인과 PR 승인은
별도 저장소 절차로 유지한다.

## 실행 가능한 계약

`fixtures/tmap-route-poc/golden-matrix.json`은 다음 request를 고정한다.

- 공개 장소 대표점 WGS84 좌표와 `longitude, latitude` 순서
- `2026-09-15T09:00:00+09:00` 출발 및 승인 출발시각 구간
- 10개 구간 × `PEDESTRIAN`, `DRIVING`, `PUBLIC_TRANSIT`의 정확한 30-case 결합
- TMAP 보행·자동차 허용 host/path와 대중교통 TMAP 호출 금지

`scripts/tmap_route_poc.py`는 30-case를 생성하고 response에서 개별 시간·거리·요금·geometry를
즉시 버린 뒤 성공 여부, 안전한 reason code와 필드 가용성만 집계한다. 누락·중복·출발시각
불일치면 판정을 거부하고 quota·timeout·provider 장애를 원문 없이 분류한다. 키가 없으면
네트워크 호출을 시작하지 않고 `APPROVED_TMAP_KEY_NOT_PRESENT`로 skip한다.

`fixtures/tmap-route-poc/deterministic-provider-responses.json`은 parser·집계 회귀만 검증하는
`SYNTHETIC_CONTRACT_ONLY` fixture이며 live evidence나 공급자 적합성 근거로 사용하지 않는다.

## 적용한 우리 측 기준

`Timing-Jeju/jeju_AI`의 source contract와 기존 라이브 인수를 기준으로 한다.

- TMAP은 `tmap.pedestrian`, `tmap.driving`만 승인한다.
- 대중교통의 노선·서비스데이·stop time은 공식 시간표와 TAGO 근거를 사용한다.
- TMAP은 대중교통의 access/transfer/egress 보행에만 사용할 수 있다.
- TMAP 원문, 상세 geometry, 요청 URL/query, 사용자 위치 이력과 정규화 구간 수치는 영속 저장하지 않는다.
- 프로세스 메모리 캐시는 최대 23시간 50분 이내에 만료한다.

기계 판독 가능한 10개 고정 구간과 정책은
`fixtures/tmap-route-poc/golden-matrix.json`에 있다. 정적 장소명은 사용자 위치 이력이 아니며,
라이브 호출 결과의 시간·거리·요금은 이 저장소에 기록하지 않는다.

## 기존 검증 근거

`jeju_AI`의 `d9cbd0d256e5bfe6d8c4f0d125d2b5fb0f710b2a`에서 다음을 확인했다.

- `config/data_sources.toml`: 보행·차량 source 승인과 `MEMORY_ONLY_LT_24H` 계약
- `docs/reports/2026-08-17-v06-live-acceptance.md`: 대표 차량 3구간 통과
- `docs/reports/2026-08-24-opening-hours-generation-integration.md`: 실제 보행 27회 통과
- `docs/reports/2026-08-18-v06-consolidated-final-acceptance.md`: 공식 시간표와 TMAP 보행 역할 분리

기존 검증은 TMAP 보행·차량 연결 가능성을 보여주지만 Issue #40이 요구하는 현재 시점의
10구간 × 3모드 전체 실측이나 TMAP 대중교통 승인을 증명하지 않는다. 확인하지 않은
duration, fare, walk segment를 과거 자료에서 새로 만들지 않는다.

Issue 댓글의 2026-08-23 sanitized aggregate에는 당시 30건 중 자동차 10, 보행 10,
대중교통 3건 성공과 대중교통 quota 7건이 기록돼 있다. 그러나 case별 artifact와 당시
커밋은 현재 원격에서 소실됐고 TMAP 대중교통은 현재 승인 source가 아니므로 이 aggregate를
qualifying evidence나 현재 판정의 정량 근거로 사용하지 않는다.

## DEFER 이후 경계

#41은 provider-neutral port를 만들고 TMAP 구현을 기본 비활성화한다. 보행·차량 TMAP은
FastAPI 프로세스의 승인된 on-demand adapter 경계에 남기며 Spring DB snapshot이나 retention
대상으로 가져오지 않는다. 대중교통은 공식 시간표와 TAGO를 계속 사용한다.

승인된 키로 새 PoC를 수행할 때도 저장 가능한 결과는 요청별 성공 여부, 필드 존재율,
안전한 reason code와 집계 latency뿐이다. 원문, geometry, 요청 URL/query, 좌표, 개별 구간의
시간·거리·요금은 파일·DB·로그에 남기지 않는다.

## 승인된 판정

Architecture Owner와 Product Owner는 다음 경계를 승인했다.

- 최종 판정은 `DEFER`다.
- #41은 provider-neutral port와 TMAP 기본 비활성화 경계를 구현한다.
- 대중교통은 공식 시간표와 TAGO를 사용한다.
- TMAP raw·geometry·사용자 위치·개별 route metric을 영속 저장하지 않는다.
- 신규 live 재실측은 별도 승인된 키와 비저장 실행 조건이 있을 때만 수행한다.
