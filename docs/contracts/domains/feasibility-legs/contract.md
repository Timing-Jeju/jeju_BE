# 가능성 계산·이동 구간 계약 v1

이 문서는 Issue #90이 소유하는 실행 가능한 공개 API 계약이다. 공통 인증, 멱등성, Problem Details는 `timing-jeju-rest-contract/v1`을 상속한다. 구현 소유자는 접수 #55, 결과 조회 #97, 구간 상세 #56이다.

## 공개 경계

| Method | Path | 구현 소유자 | 의미 |
|---|---|---:|---|
| POST | `/api/v1/trips/{tripId}/feasibility-runs` | #55 | active 일정의 계산 run을 멱등 접수하고 `202`를 반환한다. 요청 thread는 worker·MCP·외부 API를 호출하지 않는다. |
| GET | `/api/v1/trips/{tripId}/feasibility-runs/{runId}` | #97 | 저장된 run 상태와 결과만 SELECT하여 반환한다. |
| GET | `/api/v1/trips/{tripId}/schedule-versions/{versionId}/legs/{legId}` | #56 | 불변 일정 버전의 구간과 저장 snapshot 근거를 조회한다. |

모든 endpoint는 Bearer 인증이 필수이며 canonical JWT `sub`로만 owner를 판정한다. 다른 owner, trip, version 또는 lineage의 식별자는 `404`로 은닉한다.

## 상태·presence·합계

POST 성공은 `202` body와 함께 concrete polling URL인 `Location`, 1~60초 정수인 `Retry-After`를 필수로 반환한다. `Location`과 `body.pollUrl`은 요청 path의 `tripId`와 body의 `runId`를 사용한 `/api/v1/trips/{tripId}/feasibility-runs/{runId}`로 정확히 같아야 한다. `Retry-After`는 `+`, 선행 0, 앞뒤 공백이 없는 canonical ASCII 10진수다. 접수 command의 `commandInputHash`와 MCP에 실제 전달한 계산 입력의 `mcpInputHash`를 구분하며 서로 대체하지 않는다.

run 상태는 `queued`, `running`, `succeeded`, `failed`, `cancelled`뿐이다. `expired`는 run 상태가 아니라 결과 또는 facts의 만료 의미다. 모든 상태는 `responseTime`, `startedAt`, `factsSnapshotAt`, `sourceDataVersion`, `provenance`, `result`, `failure` key를 닫힌 response로 반환한다. pre-MCP failed/cancelled는 started/facts/source가 모두 null이고, 시작된 terminal 상태는 세 필드가 모두 non-null이며 저장된 MCP provenance를 유지한다. 둘을 섞은 hybrid는 거부한다. `failure`는 `{code, detail, retryable}`만 허용하며 원천 오류문을 포함하지 않는다. 상태별 정확한 nullability는 `success.json`의 다섯 상태와 네 terminal variant가 기준이다.

구간의 `totalMinutes`는 `walkMinutes + waitMinutes + rideMinutes + transferMinutes`이며 `arrivalAt - departureAt`과 일치해야 한다. top-level `walkMinutes`는 `from.walkMinutes + to.walkMinutes`이고, transit fixture는 원 명세의 10분/13분을 그대로 보존한다. `transferMinutes`는 순서대로 정렬된 각 환승의 `departureAt - arrivalAt` 합계다. `transportMode`는 `public_transit | car | walk` discriminator이고 각 mode의 `route`는 서로 다른 닫힌 schema를 사용한다. `from/to`, transit 정류장 ID·이름, 정렬된 `transferStops`, `remainingStops`, `additionalCost`, `risk.status/reasonCodes`의 mode별 presence는 완전 fixture 세 종류가 기준이다. 요금이 제공되지 않으면 `fare=null`이고 임의의 0으로 바꾸지 않는다.

사용된 TAGO/TMAP snapshot마다 `provider`, `observedAt`, `expiresAt`, `stale`을 제공한다. `observedAt <= expiresAt`이어야 하고 응답의 `responseTime`을 기준으로 정확히 `stale == (expiresAt <= responseTime)`이다. 만료와 응답 시각이 같은 등호 경계도 stale이다. stale 조회 결과는 409가 아니라 명시적인 `stale=true`로 표현한다.

## provenance와 보안

계산 결과는 `algorithmVersion`, `contractVersion`, 서로 다른 `commandInputHash`와 `mcpInputHash`, `confidence`를 함께 반환한다. 두 hash는 모두 소문자 64자리 hex를 붙인 `sha256:<digest>` 형식이다. 성공 결과는 `resultSource`, `stale`, `expiresAt`을 포함하고 `responseTime` 기준 등호 만료도 stale로 판정한다. raw provider payload/message, token, email, user metadata 및 전체 polyline은 공개하거나 fixture에 보존하지 않는다. 오류는 실행 가능한 닫힌 Problem Details schema의 한국어 필드와 stable code, traceId만 반환한다.

오류 정의는 `name`, `code`, `status`, canonical problem `type`, fixture 이름, 적용 endpoint, 발생 condition을 하나의 exact mapping으로 관리한다. endpoint의 오류 status 집합과 problem fixture의 code/status/type이 이 mapping과 다르면 readiness 검증이 실패한다.

## 외부 linkage와 readiness

검증 가능한 Notion/Figma 근거가 제공되지 않았으므로 둘 다 `not-linked`다. metadata/example/implementation readiness도 모두 `not-ready`이며 #90이 임의로 승격하지 않는다.

## #56 경로 drift

#56 본문의 `/api/v1/trips/{tripId}/legs/{legId}`는 이전 초안이다. canonical 계약은 version owner/lineage를 path에서 폐쇄하는 `/api/v1/trips/{tripId}/schedule-versions/{versionId}/legs/{legId}`다. 후속 #56은 canonical path를 구현해야 하며 #90은 호환 endpoint, redirect, Controller 또는 DB migration을 추가하지 않는다.
