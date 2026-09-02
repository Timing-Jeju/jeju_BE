# 이동 경로 provider-neutral adapter·cache 계약

## 범위

- 외부 공개 API와 Controller를 추가하지 않는다.
- Spring application port는 provider 응답 DTO나 HTTP client에 의존하지 않는다.
- TMAP 구현은 Spring에 등록하지 않고 기본 비활성 상태를 유지한다.
- TMAP 원문·geometry·요청 좌표·개별 route metric은 영속 저장하거나 로그로 남기지 않는다.

## 정규화 모델

| 항목 | 계약 |
| --- | --- |
| mode | `PUBLIC_TRANSIT`, `RENTAL_CAR`, `TAXI`, `WALK` |
| 좌표 | 유한 WGS84 latitude/longitude |
| duration | access walk + wait + ride + transfer walk + egress walk의 정확한 합계 |
| distance | 0 이상 1,000,000m 이하 |
| fare | null 또는 0 이상 10,000,000원 이하 |
| freshness | `observedAt`, `expiresAt`, `stale=false` |
| reason | `PROVIDER_FACT` 또는 보행 전용 `ESTIMATED_WALK_TIME` |

provider가 요청과 다른 mode를 반환하거나 수치·TTL 경계를 위반하면
`INVALID_PROVIDER_RESPONSE`로 닫는다. 예외의 원문 message와 cause는 결과로 전달하지 않는다.

## Cache

- source ID와 좌표·출발시각·mode의 privacy-canonical SHA-256을 key로 사용한다.
- 같은 key의 fresh cache는 provider를 호출하지 않는다.
- 같은 key의 동시 요청은 하나의 in-flight provider 결과를 공유한다.
- `now < expiresAt`만 fresh이며 equality는 만료다.
- cache는 가장 이른 `expiresAt`에 맞춘 단일 daemon scheduler로 만료 항목을 자동 제거한다.
- lifecycle 종료 시 `close()`로 예약 작업을 취소하고 메모리 cache를 비운다.
- walk TTL은 최대 23시간 50분, rental-car/taxi는 최대 5분이다.
- public-transit은 공식 publication의 실제 expiry를 사용하되 application 상한은 24시간이다.

## 실패와 fallback

| 조건 | 결과 |
| --- | --- |
| 보행 rate limit·timeout·provider unavailable | 주입된 보수 추정이 있으면 `ESTIMATED_WALK_TIME` |
| 차량·택시·대중교통 provider 실패 | 안정 code로 실패, 수치 추정 금지 |
| malformed response | `INVALID_PROVIDER_RESPONSE`, fallback 금지 |
| 보행 추정도 실패 | `EXTERNAL_FACTS_UNAVAILABLE` |

이 계약은 #40의 `DEFER`, provider-neutral, TMAP 기본 비활성화 결정을 완화하지 않는다.
`ESTIMATED_WALK_TIME` fact는 record 생성 경계에서도 반드시 `WALK` mode로 제한한다.
