# Timing Jeju 프론트엔드 API 명세

> **#68 기능 브랜치의 통합 공개 API 27개는 Codegen READY 검증 대상이다.** `openApiDocs` 뒤 portable frontend-readiness validator의 `--mode 27` 명령이 최신 `develop`의 24개와 숙소 CRUD 3개를 exact inventory로 고정하고 operationId, media type, header, schema/example 양방향 정합성, Problem Details, 비밀정보와 내부 경로를 fail-closed로 검사한다. historical mode는 이전 inventory를 그대로 보존한다.

이 문서는 최신 `develop` 공개 Spring API와 #68 숙소 CRUD를 합친 27개 operation의 프론트엔드 인계본이다. 모든 예시는 공개 가능한 고정 fixture이며 token, provider secret, 실제 사용자 정보가 아니다. 서버가 받지 않는 필드와 문서에 없는 enum을 추가하지 않는다.

## 기준과 브랜치 준비 상태

| 상태 | 범위 | 권위 자료 |
|---|---|---|
| `develop` 사용 가능 | 공개 API 23 | `origin/develop`의 Controller/OpenAPI와 canonical contract; #45 trip PATCH/DELETE와 #49 schedule read 포함 |
| **#50 기능 브랜치** | 일정 항목 추가 POST 1 | `feat/50-schedule-item-create`의 runtime, migration, 생성 OpenAPI와 PostgreSQL 통합 테스트 |

현재 `develop`에서는 24개를 호출할 수 있다. 숙소 CRUD는 독립 리뷰와 병합 전까지 #68 기능 브랜치에서만 검증하며, 이 문서는 해당 브랜치가 실제 생성한 27-operation artifact를 기준으로 한다.

## Base URL과 인증

- 모든 경로는 API v1 상대 경로다. 프론트는 배포 환경의 `{API_BASE_URL}` 환경변수를 우선 사용하고 URL을 코드에 고정하지 않는다.
- 직접 `bootRun`한 Spring은 `http://localhost:8080`, 기본 live Compose는 `http://localhost:18080`, 시현 showcase Compose는 `http://localhost:18082` 예시를 사용한다. 서로 다른 환경의 포트로 인증 token이나 fixture 요청을 보내지 않는다.
- Swagger UI: `/swagger-ui/index.html`, OpenAPI JSON: `/v3/api-docs`.
- 일반 인증은 `Authorization: Bearer <access-token>`의 Supabase JWT다. placeholder를 실제 token으로 교체하고 저장소나 로그에 남기지 않는다.
- `인증: 필수`는 header 누락 시 `401 AUTHENTICATION_REQUIRED`, 유효하지 않은 JWT는 `401 INVALID_ACCESS_TOKEN`이다.
- `인증: 선택`은 header 생략 시 익명 호출이다. header를 보냈는데 유효하지 않으면 `401 INVALID_ACCESS_TOKEN`이다.
- Naver UserInfo의 Authorization은 예외적으로 **Supabase JWT가 아니라 Naver provider access token**이다.
- 생성 OpenAPI는 bearer 필수 endpoint마다 공통 `403`을 추가하며 현재 registry code는 `AUTH_ACCESS_DENIED`다. canonical endpoint contract에는 이 403이 없고 필수 인증 401 code도 runtime과 다르므로, 아래 endpoint별 canonical/generated status를 모두 확인하고 “알려진 계약 충돌”의 호환 지침을 따른다.

## OperationId와 code generation

통합된 27개 operation은 stable lowerCamelCase operationId를 제공한다. #49의 `tripScheduleRead`, #50의 `tripScheduleItemCreate`, #45의 `tripsUpdate`·`tripsDelete`, #68의 숙소 CRUD 3개를 유지한다. `_1` 같은 자동 suffix 또는 generic `list/read/create/update/delete`가 다시 나타나면 품질 게이트가 실패한다.

## 공통 헤더와 응답

| Header | 방향 | 계약 |
|---|---|---|
| `Authorization` | 요청 | `Bearer <access-token>`; endpoint별 필수/선택 여부 확인 |
| `Content-Type` | 요청 | JSON body가 있으면 `application/json` |
| `Accept` | 요청 | 성공 JSON은 `application/json`, 오류는 `application/problem+json` |
| `X-Trace-Id` | 응답 | 서버 생성 32자리 lowercase hex; 요청 단위 추적용 |
| `Location` | 생성 응답 | 생성된 resource의 상대 URI |
| `ETag` | 생성·수정 응답 | 큰따옴표를 포함한 strong opaque validator |
| `If-Match` | 수정 요청 | 직전 응답의 `ETag` 값을 큰따옴표까지 그대로 전달 |
| `Idempotency-Key` | 생성 요청 | endpoint별 형식과 scope 확인 |
| `Idempotency-Replayed` | 생성 응답 | HTTP wire는 textual `true` 또는 `false`; generated client schema는 boolean |

`204`는 body와 content가 없다. JSON 성공은 `application/json`, 오류는 `application/problem+json`이다.

## Problem Details

모든 오류 body는 아래 closed shape이다. `instance`는 raw path/query를 반사하지 않는 `urn:timing-jeju:problem:<traceId>`이고, `traceId`는 `X-Trace-Id`와 같다. `fieldErrors`는 없을 때도 `[]`이며 provider 원문, SQL/JWT 원문, 개인정보는 포함하지 않는다.

```json
{
  "type": "https://api.timing-jeju.example/problems/validation-failed",
  "title": "요청 값이 올바르지 않습니다.",
  "status": 400,
  "detail": "입력값을 확인해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "VALIDATION_FAILED",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

각 endpoint 표의 code가 분기 기준이다. 모든 operation에는 안전한 공통 `500 INTERNAL_SERVER_ERROR`도 적용될 수 있다.

## Cursor 사용법

cursor는 opaque하고 무결성이 보호된 문자열이다. `nextCursor`를 해석하거나 조합하지 말고 다음 요청의 `cursor`에 그대로 넣는다. `hasNext=false`이면 `nextCursor=null`이다. cursor를 받은 뒤 filter, sort, size 또는 사용자 scope를 바꾸면 `400 CURSOR_CONTEXT_MISMATCH`; 훼손된 cursor는 `400 INVALID_CURSOR`다.

- places: 기본 `size=20`, 최대 100. 위치 검색은 `distanceMeters ASC NULLS LAST, name ASC, placeId ASC`, 그 외는 `name ASC, placeId ASC`.
- saved places: 기본 `size=20`, 최대 100. sort는 `saved_at_desc`, `priority_desc`, `target_day_asc`.
- trips: 기본 `size=20`, 최대 50. `updatedAt DESC, tripId DESC`.

## ETag와 If-Match

saved place PATCH는 `If-Match`가 필수다. POST/PATCH 성공의 `ETag`를 client cache에 저장하고 다음 PATCH에 그대로 보낸다. 누락·형식 오류는 `400 INVALID_REQUEST`, stale 값은 `409 SAVED_PLACE_VERSION_CONFLICT`다. 여행 POST도 strong `ETag`를 반환하지만 이 문서 범위의 trip GET에는 조건부 요청이 없다.

## Idempotency replay

- saved place POST: `Idempotency-Key`는 `^[A-Za-z0-9._:-]{1,128}$`, scope는 `canonicalSub + POST + canonical path`, TTL은 terminal response 뒤 24시간이다. 같은 key+canonical payload는 원본 status/body/Location/ETag를 재사용하고 `Idempotency-Replayed: true`만 덮는다. 다른 payload는 `409 IDEMPOTENCY_PAYLOAD_CONFLICT`.
- trip POST: lowercase canonical UUID key, scope는 `canonicalSub + POST + /api/v1/trips`, TTL 24시간이다. 같은 payload는 원본 `201`을 replay한다. 다른 payload 또는 in-progress/reused key는 `409 IDEMPOTENCY_KEY_REUSED`.
- timeout 뒤에는 새 key를 만들기 전에 같은 key와 같은 body로 재시도한다.
- HTTP header 값은 전송 계층에서 문자열이지만 OpenAPI 의미 schema는 `boolean`이다. 생성 client 또는 transport adapter가 textual `true|false`를 boolean으로 변환한다. #34 clean snapshot은 이 header schema 자체가 빠져 있어 병합 artifact에서 validator 통과 전 보완해야 한다.

## null, 생략, 기본값

- response에서 required와 nullable은 별개다. 예를 들어 `nextCursor`, `totalScore`, `scoreProvenance`는 **필드는 항상 존재**하지만 값은 `null`일 수 있다.
- profile PATCH: `nickname`, `locale` 생략은 기존 값 보존, explicit `null`은 거부. 최소 한 필드가 필요하다.
- saved place POST: `memo` 생략/null → null, `tags` 생략/null → `[]`, `priority` 생략/null → `0`, `targetDay` 생략/null → null.
- saved place PATCH: 생략은 보존, `memo`/`targetDay` null은 clear, `tags` null은 `[]`, `priority` null은 `0`; array는 전체 교체다.
- trip POST: `timezone="Asia/Seoul"`, `userPace="normal"`, `transportModes=[public_transit priority 1 primary true]`가 생략 기본값이다. optional 필드의 explicit null은 거부한다.
- places 익명 응답: 목록은 `saved=false`, `memo=null`, `tags=[]`; 상세는 `saved.value=false`, `saved.memo=null`, `saved.tags=[]`.
- weather category 값은 필드가 항상 존재하되 원천 category가 없으면 null이다.

## Endpoints

### `GET /api/v1/auth/social/providers`

operationId: `authSocialProvidersList` · Codegen: **READY** · Canonical statuses: `not-defined` · Generated OpenAPI statuses: `200,500` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 없음 · query/path/body 없음 · 성공 `200 application/json`. 반환 순서는 `google`, `kakao`, `custom:naver`의 고정 catalog 순서이며 환경에서 활성화한 항목만 포함한다. id는 Supabase `signInWithOAuth` provider 값이다. 오류: `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/auth/social/providers HTTP/1.1
Accept: application/json
```

```json
{}
```

**성공 예시**

```json
{
  "providers": [
    {"id": "google", "displayName": "Google"},
    {"id": "kakao", "displayName": "Kakao"},
    {"id": "custom:naver", "displayName": "Naver"}
  ]
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/internal-server-error",
  "title": "내부 서버 오류가 발생했습니다.",
  "status": 500,
  "detail": "요청을 처리하는 중 내부 오류가 발생했습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INTERNAL_SERVER_ERROR",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/auth/social/naver/userinfo`

operationId: `authNaverUserInfoRead` · Codegen: **READY** · Canonical statuses: `not-defined` · Generated OpenAPI statuses: `200,401,403,422,429,500,502,503,504` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 공개 adapter지만 `Authorization` 필수. 값은 `Bearer <naver-provider-access-token>`이며 Supabase JWT나 query `access_token`을 보내지 않는다. provider token 길이는 최대 256자다. 성공 `200`; `sub`, `email` 필수, `name`, `preferred_username`, `picture`는 값이 있을 때만 key가 존재한다.

오류 matrix: `401 SOCIAL_NAVER_TOKEN_INVALID | SOCIAL_NAVER_UPSTREAM_UNAUTHORIZED`; `403 SOCIAL_NAVER_UPSTREAM_FORBIDDEN`; `422 SOCIAL_NAVER_EMAIL_REQUIRED`; `429 SOCIAL_NAVER_RATE_LIMITED`; `502 SOCIAL_NAVER_UPSTREAM_UNAVAILABLE | SOCIAL_NAVER_UPSTREAM_INVALID_RESPONSE | SOCIAL_NAVER_UPSTREAM_RESPONSE_TOO_LARGE`; `503 SOCIAL_NAVER_OVERLOADED | SOCIAL_NAVER_UPSTREAM_RATE_LIMITED`; `504 SOCIAL_NAVER_UPSTREAM_TIMEOUT`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/auth/social/naver/userinfo HTTP/1.1
Authorization: Bearer <naver-provider-access-token>
Accept: application/json
```

```json
{}
```

**성공 예시**

```json
{
  "sub": "naver-public-subject-example",
  "email": "naver-example@example.invalid",
  "name": "제주 사용자",
  "preferred_username": "제주 사용자",
  "picture": "https://example.invalid/profile.png"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/social-naver-email-required",
  "title": "필수 정보가 누락되었습니다.",
  "status": 422,
  "detail": "이메일 제공 동의가 필요합니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "SOCIAL_NAVER_EMAIL_REQUIRED",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/me`

operationId: `profileRead` · Codegen: **READY** · Canonical statuses: `200,401,503` · Generated OpenAPI statuses: `200,401,403,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 필수 · query/path/body 없음 · canonical JWT sub 소유 profile을 생성 보장한 뒤 `200`. 모든 response key는 필수다. `email`, `nickname`, `profileImageUrl`은 nullable; `locale`은 `ko-KR`; providers는 `google|kakao|custom:naver`의 unique stable array. 오류: `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `503 PROFILE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/me HTTP/1.1
Authorization: Bearer <access-token>
Accept: application/json
```

```json
{}
```

**성공 예시**

```json
{
  "userId": "18000000-0000-0000-0000-000000000018",
  "email": "user@example.invalid",
  "nickname": "제주 여행자",
  "profileImageUrl": null,
  "locale": "ko-KR",
  "providers": ["google"],
  "onboardingCompleted": true,
  "updatedAt": "2026-08-25T00:00:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/profile-data-unavailable",
  "title": "프로필 조회 불가",
  "status": 503,
  "detail": "프로필 데이터를 불러올 수 없습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "PROFILE_DATA_UNAVAILABLE",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `PATCH /api/v1/me`

operationId: `profileUpdate` · Codegen: **READY** · Canonical statuses: `200,400,401,409,503` · Generated OpenAPI statuses: `200,400,401,403,409,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 필수 · `Content-Type: application/json`. closed body에서 `nickname`(trim 후 1..50자), `locale`(`ko-KR`) 중 최소 하나. 생략은 보존, null/알 수 없는 field/email/image/providers 입력은 거부. 성공 `200`. 오류: `400 INVALID_PROFILE_LEGAL_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `409 PROFILE_CONFLICT`; `503 PROFILE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
PATCH /api/v1/me HTTP/1.1
Authorization: Bearer <access-token>
Content-Type: application/json
Accept: application/json
```

```json
{
  "nickname": "제주 산책자",
  "locale": "ko-KR"
}
```

**성공 예시**

```json
{
  "userId": "18000000-0000-0000-0000-000000000018",
  "email": "user@example.invalid",
  "nickname": "제주 산책자",
  "profileImageUrl": null,
  "locale": "ko-KR",
  "providers": ["google"],
  "onboardingCompleted": true,
  "updatedAt": "2026-08-25T00:01:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/invalid-profile-legal-request",
  "title": "요청 형식 오류",
  "status": 400,
  "detail": "프로필 수정 요청 형식이 올바르지 않습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INVALID_PROFILE_LEGAL_REQUEST",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": [{"field": "nickname", "reason": "크기는 1에서 50 사이여야 합니다"}]
}
```

### `GET /api/v1/legal-documents`

operationId: `legalDocumentsList` · Codegen: **READY** · Canonical statuses: `200,400,401,503` · Generated OpenAPI statuses: `200,400,401,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 선택 · query `locale` optional/non-null, 허용값 `ko-KR`, 생략 기본값 `ko-KR`. 한 서버 평가 시각에 시행 중인 문서 최신 version을 조회한다. 성공 `200`; `items`는 비어 있을 수 있다. 문서 `type=terms|privacy|location`, `documentId` UUID, `contentUrl` HTTPS, `required` boolean. 오류: `400 INVALID_PROFILE_LEGAL_REQUEST`; `401 INVALID_ACCESS_TOKEN`; `503 PROFILE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/legal-documents?locale=ko-KR HTTP/1.1
Accept: application/json
```

```json
{"locale": "ko-KR"}
```

**성공 예시**

```json
{
  "evaluatedAt": "2026-08-25T00:00:00Z",
  "locale": "ko-KR",
  "items": [
    {
      "documentId": "19000000-0000-0000-0000-000000000019",
      "type": "terms",
      "version": "1.0.0",
      "title": "서비스 이용약관",
      "contentUrl": "https://example.invalid/legal/terms/1.0.0",
      "required": true,
      "effectiveAt": "2026-08-01T00:00:00Z"
    }
  ]
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/invalid-profile-legal-request",
  "title": "요청 형식 오류",
  "status": 400,
  "detail": "프로필 수정 요청 형식이 올바르지 않습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INVALID_PROFILE_LEGAL_REQUEST",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": [{"field": "locale", "reason": "허용 값은 ko-KR입니다"}]
}
```

### `PUT /api/v1/me/consents`

operationId: `legalConsentsUpdate` · Codegen: **READY** · Canonical statuses: `200,400,401,409,422,503` · Generated OpenAPI statuses: `200,400,401,403,409,422,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 필수 · closed JSON body. `consents` 1..20개, 각 item은 UUID `documentId`와 boolean `agreed` 필수, documentId 중복 금지. 현재 active document version에 원자 반영한다. 성공 `200`. 오류: `400 INVALID_PROFILE_LEGAL_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `409 PROFILE_CONFLICT`; `422 LEGAL_CONSENT_REQUIRED`; `503 PROFILE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
PUT /api/v1/me/consents HTTP/1.1
Authorization: Bearer <access-token>
Content-Type: application/json
Accept: application/json
```

```json
{
  "consents": [
    {"documentId": "19000000-0000-0000-0000-000000000019", "agreed": true}
  ]
}
```

**성공 예시**

```json
{
  "requiredConsentsSatisfied": true,
  "updatedAt": "2026-08-25T00:02:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/legal-consent-required",
  "title": "필수 동의 필요",
  "status": 422,
  "detail": "현재 시행 중인 필수 법정 문서에 모두 동의해야 합니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "LEGAL_CONSENT_REQUIRED",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/places`

operationId: `placesList` · Codegen: **READY** · Canonical statuses: `200,400,401,422,429,503` · Generated OpenAPI statuses: `200,400,401,422,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 선택. query는 모두 optional/non-null: `query` trim 1..100자, `category` `^(?:[A-Z]{2}|content-type:[0-9]{1,10})$`, `regionCode` `^[a-z0-9][a-z0-9_-]{0,49}$`, `lat` 33..34와 `lng` 126..127은 쌍으로 사용, `radiusMeters` 100..50000(좌표가 있을 때, 기본 10000), `cursor` 1..2048, `size` 1..100(기본 20), `savedOnly` boolean(기본 false). category는 runtime Controller/OpenAPI의 public wire pattern을 따른다. 익명 `savedOnly=true`는 `401 AUTHENTICATION_REQUIRED`.

성공 `200`. 오류: `400 INVALID_QUERY_PARAMETER | INVALID_GEO_FILTER | CURSOR_CONTEXT_MISMATCH | INVALID_CURSOR`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `422 PLACE_QUERY_CONSTRAINT_VIOLATION`; `503 PLACE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`. canonical contract에는 `429 UPSTREAM_RATE_LIMITED`가 있으나 현재 Controller OpenAPI에는 빠져 있으므로 아래 “계약 충돌”에 기록한다.

**요청 예시**

```http
GET /api/v1/places?query=%EC%98%A4%EB%A6%84&category=content-type%3A12&regionCode=jeju-si&lat=33.4996&lng=126.5312&radiusMeters=10000&size=20 HTTP/1.1
Accept: application/json
```

```json
{
  "query": "오름",
  "category": "content-type:12",
  "regionCode": "jeju-si",
  "lat": 33.4996,
  "lng": 126.5312,
  "radiusMeters": 10000,
  "size": 20,
  "savedOnly": false
}
```

**성공 예시**

```json
{
  "items": [
    {
      "placeId": "33000000-0000-0000-0000-000000000033",
      "contentId": "public-content-33",
      "name": "새별오름",
      "category": "content-type:12",
      "regionCode": "jeju-si",
      "regionLabel": "제주시",
      "address": "제주특별자치도 제주시",
      "location": {"lat": 33.366, "lng": 126.357},
      "thumbnailUrl": null,
      "recommendedStayMinutes": 90,
      "recommendedStaySource": "category_default",
      "recommendedStayPolicyVersion": "v1",
      "recommendedStayEffectiveAt": "2026-08-01T00:00:00Z",
      "recommendedStayUpdatedAt": "2026-08-01T00:00:00Z",
      "operationsSummary": null,
      "distanceMeters": 1250,
      "dataFreshness": {"provider": "TOUR_API", "observedAt": "2026-08-25T00:00:00Z", "expiresAt": "2026-08-26T00:00:00Z", "stale": false},
      "saved": false,
      "memo": null,
      "tags": []
    }
  ],
  "page": {"size": 20, "hasNext": true, "nextCursor": "opaque-public-cursor-example"}
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/invalid-geo-filter",
  "title": "요청 위치 조건이 올바르지 않습니다",
  "status": 400,
  "detail": "위도와 경도는 함께 입력하고 제주 범위와 반경을 확인해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INVALID_GEO_FILTER",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/places/{placeId}`

operationId: `placesRead` · Codegen: **READY** · Canonical statuses: `200,400,401,404,503` · Generated OpenAPI statuses: `200,400,401,404,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 선택. `placeId`는 lowercase canonical UUID. active 공개 장소의 상세, 최대 20 images, 최대 5 unique nearbyStops를 반환한다. 성공 `200`. 오류: `400 INVALID_QUERY_PARAMETER`; `401 INVALID_ACCESS_TOKEN`; `404 PLACE_NOT_FOUND`; `503 PLACE_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/places/33000000-0000-0000-0000-000000000033 HTTP/1.1
Accept: application/json
```

```json
{"placeId": "33000000-0000-0000-0000-000000000033"}
```

**성공 예시**

```json
{
  "placeId": "33000000-0000-0000-0000-000000000033",
  "contentId": "public-content-33",
  "name": "새별오름",
  "category": "content-type:12",
  "regionCode": "jeju-si",
  "regionLabel": "제주시",
  "address": "제주특별자치도 제주시",
  "location": {"lat": 33.366, "lng": 126.357},
  "thumbnailUrl": null,
  "recommendedStayMinutes": 90,
  "recommendedStaySource": "category_default",
  "recommendedStayPolicyVersion": "v1",
  "recommendedStayEffectiveAt": "2026-08-01T00:00:00Z",
  "recommendedStayUpdatedAt": "2026-08-01T00:00:00Z",
  "operationsSummary": null,
  "saved": {"value": false, "memo": null, "tags": []},
  "overview": "제주의 대표 오름입니다.",
  "contact": {"phone": null, "homepageUrl": null},
  "operations": {"operatingHoursText": null, "closedDaysText": null, "parkingText": null, "admissionFeeText": null},
  "images": [],
  "nearbyStops": []
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/place-not-found",
  "title": "장소를 찾을 수 없습니다",
  "status": 404,
  "detail": "장소가 없거나 공개할 수 없습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "PLACE_NOT_FOUND",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/me/saved-places`

병합 후 목표 operationId: `savedPlacesList` · Codegen: **병합 artifact 검증 대기** · Canonical statuses: `200,400,401` · Generated OpenAPI statuses: `200,400,401,403,500` · 현재 feature OpenAPI success media type: `*/*` · Frontend success media type: `application/json`

**#34 병합 대기** · 인증 필수. optional/non-null query: `tag`, `category`, `regionCode`, `sort=saved_at_desc|priority_desc|target_day_asc`(기본 saved_at_desc), `cursor` 1..2048, `size` 1..100(기본 20). 성공 `200`. 오류: `400 INVALID_QUERY_PARAMETER | INVALID_CURSOR | CURSOR_CONTEXT_MISMATCH`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/me/saved-places?tag=%EC%98%A4%EB%A6%84&sort=priority_desc&size=20 HTTP/1.1
Authorization: Bearer <access-token>
Accept: application/json
```

```json
{"tag": "오름", "sort": "priority_desc", "size": 20}
```

**성공 예시**

```json
{
  "items": [
    {
      "placeId": "34000000-0000-0000-0000-000000000034",
      "name": "새별오름",
      "category": "content-type:12",
      "regionLabel": "제주시",
      "thumbnailUrl": null,
      "recommendedStayMinutes": 90,
      "memo": "노을 시간 방문",
      "tags": ["노을", "오름"],
      "priority": 5,
      "targetDay": 2,
      "savedAt": "2026-08-25T00:00:00Z",
      "updatedAt": "2026-08-25T00:00:00Z"
    }
  ],
  "page": {"size": 20, "hasNext": false, "nextCursor": null}
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/cursor-context-mismatch",
  "title": "커서의 조회 조건이 현재 요청과 다릅니다",
  "status": 400,
  "detail": "변경한 조건으로 처음부터 다시 조회해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "CURSOR_CONTEXT_MISMATCH",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `POST /api/v1/me/saved-places`

병합 후 목표 operationId: `savedPlacesCreate` · Codegen: **병합 artifact 검증 대기** · Canonical statuses: `200,201,400,401,404,409,422` · Generated OpenAPI statuses: `200,201,400,401,403,404,409,422,500` · 현재 feature OpenAPI success media type: `*/*` · Frontend success media type: `application/json`

**#34 병합 대기** · 인증 필수 · `Idempotency-Key` 필수(`^[A-Za-z0-9._:-]{1,128}$`). closed body: `placeId` canonical UUID 필수; `memo` nullable 최대 2000자; `tags` nullable 최대 20개, item trim+nfc 1..50자 후 deduplicate/sort; `priority` nullable 0..5; `targetDay` nullable 1..365. 첫 생성 `201`, 동일한 현재 resource는 `200`; header `Location`, `ETag`, `Idempotency-Replayed`.

`Idempotency-Replayed` HTTP serialization: textual `true|false`; OpenAPI schema: #34 snapshot에서 누락(목표 `boolean`).

오류: `400 INVALID_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `404 PLACE_NOT_FOUND`; `409 IDEMPOTENCY_PAYLOAD_CONFLICT | SAVED_PLACE_ALREADY_EXISTS`; `422 SAVED_PLACE_CONSTRAINT_VIOLATION`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
POST /api/v1/me/saved-places HTTP/1.1
Authorization: Bearer <access-token>
Idempotency-Key: saved-place-create-34
Content-Type: application/json
Accept: application/json
```

```json
{
  "placeId": "34000000-0000-0000-0000-000000000034",
  "memo": "노을 시간 방문",
  "tags": ["오름", "노을"],
  "priority": 5,
  "targetDay": 2
}
```

**성공 예시**

```json
{
  "placeId": "34000000-0000-0000-0000-000000000034",
  "name": "새별오름",
  "category": "content-type:12",
  "regionLabel": "제주시",
  "thumbnailUrl": null,
  "recommendedStayMinutes": 90,
  "memo": "노을 시간 방문",
  "tags": ["노을", "오름"],
  "priority": 5,
  "targetDay": 2,
  "savedAt": "2026-08-25T00:00:00Z",
  "updatedAt": "2026-08-25T00:00:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/idempotency-payload-conflict",
  "title": "같은 멱등성 키의 요청 내용이 다릅니다",
  "status": 409,
  "detail": "새 Idempotency-Key로 다시 요청해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "IDEMPOTENCY_PAYLOAD_CONFLICT",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `PATCH /api/v1/me/saved-places/{placeId}`

병합 후 목표 operationId: `savedPlacesUpdate` · Codegen: **병합 artifact 검증 대기** · Canonical statuses: `200,400,401,404,409,422` · Generated OpenAPI statuses: `200,400,401,403,404,409,422,500` · 현재 feature OpenAPI success media type: `*/*` · Frontend success media type: `application/json`

**#34 병합 대기** · 인증 필수 · canonical UUID `placeId` · strong `If-Match` 필수(`^"[A-Za-z0-9._:-]{1,128}"$`). body는 `memo`, `tags`, `priority`, `targetDay` 중 최소 하나이며 semantics는 공통 null 표를 따른다. 성공 `200`과 새 `ETag`. 오류: `400 INVALID_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `404 SAVED_PLACE_NOT_FOUND`; `409 SAVED_PLACE_VERSION_CONFLICT`; `422 SAVED_PLACE_CONSTRAINT_VIOLATION`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
PATCH /api/v1/me/saved-places/34000000-0000-0000-0000-000000000034 HTTP/1.1
Authorization: Bearer <access-token>
If-Match: "saved-place.34.v1"
Content-Type: application/json
Accept: application/json
```

```json
{
  "memo": null,
  "tags": ["오름"],
  "priority": 3
}
```

**성공 예시**

```json
{
  "placeId": "34000000-0000-0000-0000-000000000034",
  "name": "새별오름",
  "category": "content-type:12",
  "regionLabel": "제주시",
  "thumbnailUrl": null,
  "recommendedStayMinutes": 90,
  "memo": null,
  "tags": ["오름"],
  "priority": 3,
  "targetDay": 2,
  "savedAt": "2026-08-25T00:00:00Z",
  "updatedAt": "2026-08-25T00:05:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/saved-place-version-conflict",
  "title": "관심 장소가 이미 변경되었습니다",
  "status": 409,
  "detail": "최신 관심 장소를 조회한 뒤 다시 수정해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "SAVED_PLACE_VERSION_CONFLICT",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `DELETE /api/v1/me/saved-places/{placeId}`

병합 후 목표 operationId: `savedPlacesDelete` · Codegen: **병합 artifact 검증 대기** · Canonical statuses: `204,400,401,404` · Generated OpenAPI statuses: `204,400,401,403,404,500` · Generated success media type: `none` · Frontend success media type: `none`

**#34 병합 대기** · 인증 필수 · canonical UUID `placeId`; body와 `If-Match` 없음. 첫 삭제는 body 없는 `204`; 이미 삭제됐거나 타 소유자는 `404`로 은닉. 오류: `400 INVALID_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `404 SAVED_PLACE_NOT_FOUND`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
DELETE /api/v1/me/saved-places/34000000-0000-0000-0000-000000000034 HTTP/1.1
Authorization: Bearer <access-token>
```

request body 없음. 성공 `204`의 response content 없음.

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/saved-place-not-found",
  "title": "관심 장소를 찾을 수 없습니다",
  "status": 404,
  "detail": "요청한 관심 장소가 없거나 접근할 수 없습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "SAVED_PLACE_NOT_FOUND",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/trips`

operationId: `tripsList` · Codegen: **READY** · Canonical statuses: `200,400,401,503` · Generated OpenAPI statuses: `200,400,401,403,500,503` · Generated success media type: `application/json`

`develop` 사용 가능 · 인증 필수. optional/non-null query: `status=draft|generating|planned|live|completed|cancelled|failed`, `sort=updated_at_desc`, `cursor` 1..2048, `size` 1..50(기본 20). 알 수 없는/중복 query도 거부한다. 성공 `200`. nullable required fields `activeScheduleVersionId`, `totalScore`, `scoreProvenance`는 항상 key가 있다. 오류: `400 INVALID_QUERY_PARAMETER | INVALID_CURSOR | CURSOR_CONTEXT_MISMATCH`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `503 TRIP_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/trips?status=draft&sort=updated_at_desc&size=20 HTTP/1.1
Authorization: Bearer <access-token>
Accept: application/json
```

```json
{"status": "draft", "sort": "updated_at_desc", "size": 20}
```

**성공 예시**

```json
{
  "items": [
    {
      "tripId": "44000000-0000-0000-0000-000000000044",
      "title": "제주 3박 4일",
      "status": "draft",
      "startDate": "2026-09-10",
      "endDate": "2026-09-13",
      "timezone": "Asia/Seoul",
      "activeScheduleVersionId": null,
      "totalScore": null,
      "scoreProvenance": null,
      "createdAt": "2026-08-25T00:00:00Z",
      "updatedAt": "2026-08-25T00:00:00Z"
    }
  ],
  "page": {"size": 20, "hasNext": false, "nextCursor": null}
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/invalid-query-parameter",
  "title": "조회 조건이 올바르지 않습니다",
  "status": 400,
  "detail": "여행 목록 조회 조건을 확인해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "INVALID_QUERY_PARAMETER",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `POST /api/v1/trips`

operationId: `tripsCreate` · Codegen: **READY** · Canonical statuses: `201,400,401,409,422,503` · Generated OpenAPI statuses: `201,400,401,403,409,422,500,503` · Generated success media type: `application/json`

`develop` 사용 가능 · 인증 필수 · lowercase canonical UUID `Idempotency-Key` 필수. closed body는 `title` trim+nfc 1..100자, `startDate`, `endDate` 필수; 최대 30일 inclusive. `timezone=Asia/Seoul`; `userPace=slow|normal|fast`; `transportModes` 1..3개, mode는 `public_transit|rental_car|taxi`, priority 1..3 연속/unique, primary 정확히 하나이자 priority 1. body 최대 1 MiB. 성공 `201` + `Location`, revision 기반 `ETag`, `Idempotency-Replayed`.

`Idempotency-Replayed` HTTP serialization: textual `true|false`; OpenAPI schema: `boolean`. #44 최종 clean HEAD `9a4c4b2`에서 `Idempotency-Key`는 required canonical UUID이며 예시도 같은 형식을 사용한다.

오류: `400 INVALID_REQUEST | IDEMPOTENCY_KEY_REQUIRED | IDEMPOTENCY_KEY_INVALID`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `409 IDEMPOTENCY_KEY_REUSED | PROFILE_CONFLICT`; `422 TRIP_CONSTRAINT_VIOLATION`; `503 TRIP_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
POST /api/v1/trips HTTP/1.1
Authorization: Bearer <access-token>
Idempotency-Key: 44000000-0000-0000-0000-000000000044
Content-Type: application/json
Accept: application/json
```

```json
{
  "title": "제주 3박 4일",
  "startDate": "2026-09-10",
  "endDate": "2026-09-13",
  "timezone": "Asia/Seoul",
  "userPace": "normal",
  "transportModes": [
    {"mode": "public_transit", "priority": 1, "primary": true}
  ]
}
```

**성공 예시**

```json
{
  "tripId": "44000000-0000-0000-0000-000000000044",
  "title": "제주 3박 4일",
  "status": "draft",
  "startDate": "2026-09-10",
  "endDate": "2026-09-13",
  "timezone": "Asia/Seoul",
  "userPace": "normal",
  "transportModes": [{"mode": "public_transit", "priority": 1, "primary": true}],
  "days": [
    {"dayId": "44000000-0000-0000-0001-000000000044", "dayNo": 1, "date": "2026-09-10"},
    {"dayId": "44000000-0000-0000-0002-000000000044", "dayNo": 2, "date": "2026-09-11"},
    {"dayId": "44000000-0000-0000-0003-000000000044", "dayNo": 3, "date": "2026-09-12"},
    {"dayId": "44000000-0000-0000-0004-000000000044", "dayNo": 4, "date": "2026-09-13"}
  ],
  "activeScheduleVersionId": null,
  "totalScore": null,
  "scoreProvenance": null,
  "scheduleEffect": "none",
  "regenerationRequired": false,
  "createdAt": "2026-08-25T00:00:00Z",
  "updatedAt": "2026-08-25T00:00:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.example/problems/idempotency-key-reused",
  "title": "멱등성 키를 재사용할 수 없습니다.",
  "status": 409,
  "detail": "새 Idempotency-Key로 다시 요청해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/trips/{tripId}`

operationId: `tripsRead` · Codegen: **READY** · Canonical statuses: `200,400,401,404,503` · Generated OpenAPI statuses: `200,400,401,403,404,500,503` · Generated success media type: `application/json`

`develop` 사용 가능 · 인증 필수 · `tripId` lowercase canonical UUID. canonical JWT sub를 조회 조건에 포함하며 타 소유자도 `404`다. 성공 `200`은 revision 기반 `ETag`를 함께 반환하며 response shape과 nullable 의미는 POST 성공과 같다. 일정 점수가 null이 아니면 `scoreProvenance`도 non-null이고 active schedule의 최신 succeeded feasibility run을 가리킨다. 오류: `400 INVALID_REQUEST`; `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`; `404 TRIP_NOT_FOUND`; `503 TRIP_DATA_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/trips/44000000-0000-0000-0000-000000000044 HTTP/1.1
Authorization: Bearer <access-token>
Accept: application/json
```

```json
{"tripId": "44000000-0000-0000-0000-000000000044"}
```

**성공 예시**

```json
{
  "tripId": "44000000-0000-0000-0000-000000000044",
  "title": "제주 3박 4일",
  "status": "draft",
  "startDate": "2026-09-10",
  "endDate": "2026-09-13",
  "timezone": "Asia/Seoul",
  "userPace": "normal",
  "transportModes": [{"mode": "public_transit", "priority": 1, "primary": true}],
  "days": [
    {"dayId": "44000000-0000-0000-0001-000000000044", "dayNo": 1, "date": "2026-09-10"},
    {"dayId": "44000000-0000-0000-0002-000000000044", "dayNo": 2, "date": "2026-09-11"},
    {"dayId": "44000000-0000-0000-0003-000000000044", "dayNo": 3, "date": "2026-09-12"},
    {"dayId": "44000000-0000-0000-0004-000000000044", "dayNo": 4, "date": "2026-09-13"}
  ],
  "activeScheduleVersionId": null,
  "totalScore": null,
  "scoreProvenance": null,
  "scheduleEffect": "none",
  "regenerationRequired": false,
  "createdAt": "2026-08-25T00:00:00Z",
  "updatedAt": "2026-08-25T00:00:00Z"
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/trip-not-found",
  "title": "여행을 찾을 수 없습니다",
  "status": 404,
  "detail": "요청한 여행이 없거나 접근할 수 없습니다.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "TRIP_NOT_FOUND",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

### `GET /api/v1/trips/{tripId}/schedule`

operationId: `tripScheduleRead` · Codegen: **#49 feature artifact READY** · Canonical statuses: `200,400,401,404` · Generated OpenAPI statuses: `200,400,401,403,404,500` · success media type: `application/json`

인증 필수. `tripId`와 optional `versionId`는 lowercase canonical UUID다. `versionId`를 생략하면 owner trip의 active pointer를, 지정하면 같은 owner/trip의 불변 버전을 조회한다. active pointer 없음, 다른 trip/owner 버전, 존재하지 않는 버전은 모두 `404 SCHEDULE_VERSION_NOT_FOUND`; 여행 없음과 cross-owner 여행은 `404 TRIP_NOT_FOUND`로 은닉한다. GET body, 빈·반복·unknown query는 `400 INVALID_REQUEST`다.

Day는 `(dayNo, dayId)`, item과 leg는 각각 `(sequenceNo, itemId)`와 `(sequenceNo, legId)` 오름차순이다. 비정상 중복 번호도 숨기지 않고 UUID tie-break 순서로 투영한다. item N개에는 정렬된 인접 pair를 잇는 leg가 정확히 `max(N-1,0)`개 있어야 하며, 불완전한 저장 행을 0이나 임의 시간으로 채우지 않고 원천 정보를 노출하지 않는 공통 `500 INTERNAL_SERVER_ERROR`로 fail-closed한다. 모든 일정·진행 시각은 `+09:00`이다. `score`는 선택한 버전의 저장 점수이고 `feasibilityStale`은 같은 버전 최신 성공 feasibility의 `observedAt <= calculatedAt <= expiresAt` 및 응답 시각 `< expiresAt` 조건으로 계산한다.

```http
GET /api/v1/trips/49000000-0000-4000-8000-000000000001/schedule?versionId=49000000-0000-4000-8000-000000000002 HTTP/1.1
Authorization: Bearer <access-token>
Accept: application/json
```

```json
{
  "tripId": "49000000-0000-4000-8000-000000000001",
  "scheduleVersion": {
    "scheduleVersionId": "49000000-0000-4000-8000-000000000002",
    "versionNo": 1,
    "status": "active",
    "sourceType": "initial",
    "baseScheduleVersionId": null,
    "score": 81,
    "feasibilityStale": false
  },
  "days": [
    {
      "dayId": "49000000-0000-4000-8000-000000000003",
      "dayNo": 1,
      "date": "2026-09-01",
      "items": [
        {
          "itemId": "49000000-0000-4000-8000-000000000004",
          "sequenceNo": 1,
          "itemType": "custom",
          "placeId": null,
          "title": "공항 도착",
          "plannedStartAt": "2026-09-01T09:00:00+09:00",
          "plannedEndAt": "2026-09-01T10:00:00+09:00",
          "stayMinutes": 60,
          "bufferAfterMinutes": 0,
          "required": true,
          "memo": null,
          "progress": null
        }
      ],
      "legs": []
    }
  ]
}
```

### `PATCH /api/v1/trips/{tripId}`

operationId: `tripsUpdate` · Codegen: **READY on #45** · Generated statuses: `200,400,401,403,404,409,422,500,503` · Generated success media type: `application/json`

인증과 `If-Match`가 필수다. `GET` 또는 직전 `PATCH`가 반환한 strong ETag를 문자 하나도 바꾸지 않고 전달한다. runtime 형식은 `"trip-{tripId}-r{revision}"`이며 weak tag, wildcard, 복수 tag, 다른 trip ID, 0 revision은 허용하지 않는다. closed body는 `title`, `startDate`, `endDate`, `timezone`, `userPace`, `transportModes` 중 한 개 이상을 포함해야 하고 모든 명시 필드는 non-null이다. 날짜는 함께 보낼 필요가 없으며 누락한 반대편 값은 현재 값으로 계산한다.

```http
PATCH /api/v1/trips/44000000-0000-0000-0000-000000000044 HTTP/1.1
Authorization: Bearer <access-token>
If-Match: "trip-44000000-0000-0000-0000-000000000044-r1"
Content-Type: application/json

{"title":"제주 버스 여행"}
```

성공 `200`은 수정된 aggregate와 증가한 revision의 `ETag`를 반환한다. 제목만 실제 변경하면 active schedule을 유지하고 `scheduleEffect=maintained`다. pace 또는 교통수단의 실제 변경은 active version을 `superseded`로 만들고 pointer와 score를 비우며 `scheduleEffect=invalidated`, `regenerationRequired=true`를 반환한다. 일정 버전이 하나라도 존재하면 날짜 또는 timezone 변경은 `409 TRIP_REGENERATION_REQUIRED`이고, 일정 버전이 없으면 날짜별 Day를 정확히 재구성한다.

오류: `400 INVALID_REQUEST | IF_MATCH_REQUIRED | INVALID_IF_MATCH`; `404 TRIP_NOT_FOUND`; `409 TRIP_VERSION_CONFLICT | TRIP_REGENERATION_REQUIRED | TRIP_TERMINAL_STATE_CONFLICT`; `422 TRIP_CONSTRAINT_VIOLATION`; `503 TRIP_DATA_UNAVAILABLE`. stale writer는 최신 `GET`과 새 ETag로 재시도하며 서버가 자동 병합하지 않는다.

### `DELETE /api/v1/trips/{tripId}`

operationId: `tripsDelete` · Codegen: **READY on #45** · Generated statuses: `204,400,401,403,404,409,500,503`

인증 필수이며 query와 request body를 허용하지 않는다. 성공은 body와 content가 없는 `204`다. 삭제는 trip aggregate만 cascade하며 공유 TourAPI fact/import, 사용자 profile과 auth identity는 보존한다. 같은 요청 반복과 타 소유자 접근은 모두 `404 TRIP_NOT_FOUND`다.

```http
DELETE /api/v1/trips/44000000-0000-0000-0000-000000000044 HTTP/1.1
Authorization: Bearer <access-token>
```

`live` 상태이거나 queued/running generation·compute·revision run이 있으면 `409 TRIP_DELETE_CONFLICT`다. `completed`, `cancelled`, `failed` 여행도 실행 중 run이 없다면 삭제할 수 있다. 그 밖의 오류는 `400 INVALID_REQUEST`, `503 TRIP_DATA_UNAVAILABLE`이다.

### `GET /api/v1/weather/forecast`

operationId: `weatherForecastRead` · Codegen: **READY** · Canonical statuses: `200,400,401,422,503` · Generated OpenAPI statuses: `200,400,401,422,500,503` · Generated success media type: `application/json` · Frontend success media type: `application/json`

`develop` 사용 가능 · 인증 선택. query `lat`(-90 exclusive..90 exclusive), `lng`(-180..180), `dateTime` 모두 필수/non-null/finite. dateTime은 `Asia/Seoul` 정시와 `+09:00`, 예: `2026-08-25T12:00:00+09:00`; 현재 정시부터 10일 이내만 지원한다. request-time KMA 호출 없이 저장된 정규화 예보를 반환한다.

성공 `200`; `contractVersion=1.0.0`, `provider=KMA`, `providerApiVersion=VilageFcstInfoService_2.0`, `forecastType=ultra_short|village`. category-derived 값은 required nullable. 오류: `400 INVALID_WEATHER_FORECAST_QUERY`; `401 INVALID_ACCESS_TOKEN`; `422 WEATHER_LOCATION_NOT_SUPPORTED | WEATHER_FORECAST_HORIZON_NOT_SUPPORTED`; `503 WEATHER_FORECAST_UNAVAILABLE`; `500 INTERNAL_SERVER_ERROR`.

**요청 예시**

```http
GET /api/v1/weather/forecast?lat=33.4996&lng=126.5312&dateTime=2026-08-25T12%3A00%3A00%2B09%3A00 HTTP/1.1
Accept: application/json
```

```json
{
  "lat": 33.4996,
  "lng": 126.5312,
  "dateTime": "2026-08-25T12:00:00+09:00"
}
```

**성공 예시**

```json
{
  "contractVersion": "1.0.0",
  "grid": {"nx": 53, "ny": 38, "regionName": "제주시"},
  "provider": "KMA",
  "providerApiVersion": "VilageFcstInfoService_2.0",
  "forecastType": "village",
  "baseDate": "2026-08-25",
  "baseTime": "05:00",
  "forecastedAt": "2026-08-25T05:00:00+09:00",
  "validAt": "2026-08-25T12:00:00+09:00",
  "temperatureC": 27.5,
  "precipitationProbabilityPercent": 20,
  "precipitationAmountMm": null,
  "precipitationType": "none",
  "skyCode": "mostly_cloudy",
  "humidityPercent": 72,
  "windSpeedMps": 3.4,
  "observedAt": "2026-08-25T05:10:00+09:00",
  "expiresAt": "2026-08-25T08:00:00+09:00",
  "stale": false,
  "fallbackUsed": false
}
```

**오류 예시**

```json
{
  "type": "https://api.timing-jeju.com/problems/weather-forecast-horizon-not-supported",
  "title": "지원하지 않는 예보 기간입니다",
  "status": 422,
  "detail": "현재 정시부터 10일 이내의 제주 현지 시각을 입력해 주세요.",
  "instance": "urn:timing-jeju:problem:0123456789abcdef0123456789abcdef",
  "code": "WEATHER_FORECAST_HORIZON_NOT_SUPPORTED",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": []
}
```

## 알려진 계약 충돌과 인계 주의사항

1. places list canonical contract는 `429 UPSTREAM_RATE_LIMITED`를 열거하지만 현재 `develop` runtime의 정규화 read-only 경로와 problem registry에는 이 분기가 없다. #182는 존재하지 않는 runtime status를 Swagger에 추가하지 않는다. owning places contract/runtime Issue가 양쪽을 정렬하기 전에는 프론트 분기 계약으로 확정하지 않는다.
2. profile PATCH의 `409 PROFILE_CONFLICT`는 runtime handler와 canonical contract에 이미 존재하므로 #182 생성 OpenAPI에도 반영했다.
3. #44 trips canonical contract는 POST 멱등성 충돌을 `IDEMPOTENCY_KEY_REUSED`로 정의한다. #34 saved places는 다른 공통 구현 계약인 `IDEMPOTENCY_PAYLOAD_CONFLICT`를 쓴다. 두 code를 하나로 합치지 않는다.
4. #34/#44/#113 통합 artifact의 readiness 표식은 배포 가능 여부나 독립 Reviewer 승인을 대신하지 않는다.
5. `ProblemDefinition.forCode`로 만든 공통/profile/legal/Naver type은 현재 source에서 `https://api.timing-jeju.example/problems/...`이고, places/saved-places/trips/weather의 domain definition 및 canonical contract는 `https://api.timing-jeju.com/problems/...`이다. 프론트는 type host를 분기 key로 쓰지 말고 안정적인 `code`를 사용한다. #182 후속에서 canonical host를 하나로 정렬해야 한다.
6. Spring 공통 인증 runtime은 필수 인증의 header 누락을 `AUTHENTICATION_REQUIRED`, 제공된 인증 정보 실패를 `INVALID_ACCESS_TOKEN`으로 구분하며, 선택 인증 endpoint는 header가 없을 때 익명 흐름을 유지한다.
7. Naver UserInfo의 생성 OpenAPI 200 media type은 #182에서 runtime과 같은 `application/json`으로 정렬했다.
8. places canonical JSON의 `endpoints[].query.category.pattern`은 stale lowercase pattern `^[a-z][a-z0-9_]{0,49}$`을 담고 있지만 같은 contract의 public `schemas.Category`, runtime `CanonicalPlaceCategory.OPEN_API_PATTERN`, generated OpenAPI는 `^(?:[A-Z]{2}|content-type:[0-9]{1,10})$`로 일치한다. 실제 public wire와 예시는 후자를 권위로 사용하며 중복 canonical endpoint.query 값은 owning contract Issue에서 정렬한다.
9. generated OpenAPI의 모든 bearer 필수 endpoint에는 canonical error matrix에 없는 `403`이 공통 추가되고 runtime code는 `AUTH_ACCESS_DENIED`다. 프론트는 현재 403을 처리하되 canonical status 정렬 전까지 이를 최종 계약으로 간주하지 않는다.
10. #44 최종 clean HEAD `9a4c4b2`와 선행 OpenAPI 보완 `88c50c3`에서 trip `Idempotency-Key`는 required canonical UUID로 정렬됐다. #34 clean snapshot의 `Idempotency-Replayed` header schema는 여전히 비어 있으므로 병합 artifact에서 boolean으로 보완돼야 한다. 요청 header는 필수로 보내고 replay header의 textual wire 값 `true|false`를 boolean으로 변환한다.
11. portable validator와 mutation test는 artifact 부재를 포함해 fail-closed다. #68 기능 브랜치는 새로 생성한 단일 27-operation artifact에서 `--mode 27` 검사를 통과해야 Codegen READY다. historical mode는 이후 operation을 allowlist 밖으로 거부한다. 기능별 문서나 fixture를 합쳐 만든 JSON은 완료 증거로 인정하지 않는다.
