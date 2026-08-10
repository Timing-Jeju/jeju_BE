# REST 공통 계약과 readiness

이 문서는 Issue #72가 소유하는 계약 템플릿과 자동 검사 기준입니다. 공개 endpoint나 개별 DTO를 추가하지 않으며, 도메인 계약 Issue #82~#94가 [`endpoint-template.json`](rest/endpoint-template.json)을 복사하지 않고 `timing-jeju-rest-contract/v1`을 명시적으로 상속합니다. local catalog가 버전 기준이며 Notion과 Figma는 같은 버전을 기록하기 전까지 `not-linked`를 유지합니다.

## 공통 상속 규칙

- 인증은 `required` 또는 `optional`만 사용합니다. 권한 판단은 검증된 JWT의 canonical `sub`만 사용하고 `user_metadata`는 사용하지 않습니다. required 요청의 token 없음은 401, optional 요청의 token 없음은 익명 흐름이고, invalid token은 두 mode 모두 401입니다. token 없음과 invalid token은 서로 다른 `code`로 분류합니다. 소유 리소스는 도메인 정책에 따라 403 또는 404로 은닉합니다.
- 생성·계산·적용 요청은 `Idempotency-Key`의 scope, TTL, replay, payload conflict, 동시 요청 규칙을 모두 명시합니다.
- 목록은 opaque cursor, size 범위, stable sort와 고유 tie-breaker를 함께 명시합니다.
- 오류 본문은 `application/problem+json`의 `type,title,status,detail,instance,code,traceId,fieldErrors`만 사용합니다. `message`, `violations`, token, PII, command payload, raw provider payload를 응답·로그·metric tag에 남기지 않습니다.
- 비동기 run 상태는 `queued/running/succeeded/failed/cancelled`뿐입니다. 접수 응답은 `Location`, `Retry-After`, 실패 조회는 `failure` object를 정의합니다. fallback 성공은 `status=succeeded`, `result_source=fallback`이고 `fallback` 상태를 만들지 않습니다. 후보 만료는 run 상태가 아니라 `expiresAt`입니다.
- 접수 재현성 hash는 `commandInputHash`, 실제 MCP wire 입력 hash는 `mcpInputHash`로 분리합니다. worker는 불변 command snapshot만 읽습니다.

각 endpoint는 허용된 HTTP method와 `/api/v1/...` path, `read/list/create/update/delete/compute/apply` operation 분류를 사용합니다. `create`, `compute`, `apply`는 `required=false`로 낮출 수 없고 `Idempotency-Key`, scope, TTL, replay, payload conflict, concurrent request 필드를 정확한 구조와 non-empty 값으로 작성합니다. 나머지 operation의 기본값은 `required=false`, `header=none`입니다. path/query/header/body schema와 owner, presence, response status, DB owner, request-time call, lineage, Figma 상태도 올바른 타입과 non-empty 값이어야 합니다.

## 구현 소유 경계

이 문서는 상세 DB schema나 migration을 구현하지 않습니다. durable command schema/migration은 #108, 위치 TTL cleanup은 #109, lease·retry·복구 worker runtime은 #74가 소유합니다. `supabase/migrations`가 운영 schema의 단일 기준이며 Flyway와 FastAPI Python 소스는 이 범위에 들어오지 않습니다.

## readiness와 버전

각 도메인은 Metadata Ready, Example Ready, Implementation Ready를 독립적으로 관리합니다.

- 각 단계는 `{ "status": "ready|not-ready", "evidence": object|null }` 구조입니다. `not-ready`의 evidence는 `null`이어야 하며 truthy 문자열이나 임의 배열로 승격할 수 없습니다.
- `Metadata Ready`: local, Notion, Figma의 `contractVersion`이 모두 같고 `localDocument/notionPage/figmaNode` 증거가 있을 때만 `ready`입니다.
- `Example Ready`: Metadata Ready 이후, 비밀정보나 실제 사용자 데이터가 없는 `requestFixture/successFixture/problemFixture` 증거가 있을 때만 `ready`입니다.
- `Implementation Ready`: Metadata와 Example Ready 이후, `controller/openApiTest/contractTest` 증거가 모두 있을 때만 `ready`입니다. 구현 전에는 승격할 수 없습니다.
- Notion/Figma가 아직 연결되지 않은 경우 `not-linked`로 두며 임의 버전을 추정하지 않습니다. 연결된 뒤에는 local 버전과 정확히 같아야 합니다.

## 자동 검사

`python3 scripts/validate_rest_contracts.py`는 catalog와 `endpoint-template.json`을 실제로 함께 읽습니다. catalog/template version, 필수 field와 default 상속 drift, method/path 중복, 필수 필드의 공백·타입, 인증·멱등성·cursor 상속, run/candidate/fallback 혼용, Problem Details 구형 필드, hash/소유권 drift, #82~#94 exactly-once 상속, 구조화 readiness와 버전 drift를 한국어 오류로 보고합니다. 이 검사는 `./scripts/quality-gate.sh`의 공통 단계에서 항상 실행됩니다.
