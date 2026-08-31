# 프로필·법정 문서 API 계약

Issue #82의 local contract version은 Issue #181의 additive 결정으로 `1.1.0`이다. 공통 REST 계약 #72와 위치정보 정책 #73을 상속하며, 기존 core 5개와 삭제 상태 extension에 프로필 이미지 extension을 추가한다. 프로필 GET/PATCH는 #18, 이미지 Storage·DB·Java 구현은 #78, 법정 문서 GET과 동의 PUT 및 migration은 #19가 구현하며 계정 삭제 API·암호화는 #61, worker는 #106이 계속 소유한다.

## Endpoint와 owner

| 구분 | Method | Path | Auth | 구현 owner |
| --- | --- | --- | --- | --- |
| core | GET | `/api/v1/me` | required JWT | #18 |
| core | PATCH | `/api/v1/me` | required JWT | #18 |
| core | DELETE | `/api/v1/me` | required JWT + recent reauth + Idempotency-Key | #61 |
| core | GET | `/api/v1/legal-documents` | anonymous 또는 optional Bearer JWT | #19 |
| core | PUT | `/api/v1/me/consents` | required JWT | #19 |
| extension | GET | `/api/v1/account-deletion-requests/{deletionRequestId}` | `X-Deletion-Status-Token` only; JWT는 ownership 근거가 아님 | #61 API, #106 worker |
| extension | GET | `/api/v1/me/profile-image` | required JWT | #78 |
| extension | PUT | `/api/v1/me/profile-image` | required JWT + `Idempotency-Key` + strong `If-Match` | #78 |

PATCH는 `nickname`, `locale`만 받으며 omitted와 null을 구분한다. 두 필드의 omitted는 기존 값을 보존하고 null은 거부한다. `email`, `providers`, provider `profileImageUrl`은 read-only다. 이미지 입력을 PATCH에 추가하지 않고 별도 PUT으로 분리했으므로 #18/#82의 closed schema와 기존 클라이언트가 유지된다. 커밋 fixture의 Bearer 값은 secret scanner가 허용하는 `Bearer <fixture-access-token>`만 사용하고, validator가 이 exact placeholder만 실제 wire grammar 검증용 생성값으로 치환한다.

## 프로필 이미지 v1.1 계약

프론트는 publishable key와 현재 사용자 JWT로 public bucket `profile-images`의 immutable identity `profile-images/{canonicalSub}/profile/{lowercaseGenerationUuid}`에 직접 INSERT한다. `profileImageObjectKey`는 bucket을 제외한 `{canonicalSub}/profile/{lowercaseGenerationUuid}`다. client는 generation마다 새 UUID를 만들고 `upsert=false`로 INSERT만 하며 authenticated Storage UPDATE와 DELETE는 ACL로 금지한다. 삭제 권한은 server cleanup worker와 Auth 삭제 전 #106에만 있고 모두 Storage API를 사용한다. Spring은 업로드 bytes를 proxy하지 않으며 사용자 업로드에 service credential을 쓰지 않는다. canonicalSub는 검증된 JWT `sub`의 lowercase UUID 문자열이다. 두 UUID segment는 version-neutral canonical parser로 검증하므로 lowercase UUIDv7 generation을 포함하며, 아래 confirm 예시는 UUIDv7이다. key는 byte-for-byte 일치해야 하고 trim, percent decoding, Unicode normalization, 확장자, uppercase UUID, 중복 slash와 요청 owner 입력을 허용하지 않는다. bucket은 upload 시 `file_size_limit=5 MiB`, `allowed_mime_types=image/jpeg,image/png,image/webp`를 적용하며 confirm도 1 byte 이상 5 MiB 이하를 재검증한다.

`GET /api/v1/me/profile-image`는 현재 storage/provider/none 상태를 같은 `ProfileImageResponse`로 반환하고 현재 version의 strong `ETag`(신규 row는 version `0`, `"profile-image-0"`)를 제공한다. version과 ETag decimal은 `0..9223372036854775807`이며 0 또는 leading zero 없는 nonzero decimal만 canonical이다. 이 endpoint로 PUT의 `If-Match` 값을 발견한다. 기존 core `GET /api/v1/me`의 `profileImageUrl`도 같은 `storage > provider > none` 우선순위를 사용하되 response field shape은 바뀌지 않고, `PATCH /api/v1/me` closed schema도 유지된다.

```http
PUT /api/v1/me/profile-image
Authorization: Bearer <supabase_access_token>
Idempotency-Key: 00000000-0000-4000-8000-000000000181
If-Match: "profile-image-0"
Content-Type: application/json

{"profileImageObjectKey":"09000000-0000-4000-8000-000000000001/profile/018f47a1-43d2-7b6e-9fa2-11a1cc32c675"}
```

확정 시 Spring은 Storage API/adapter로 `bucketId`, `name`, `ownerId`, `mimetype`, 정수 byte `size`, immutable `generation`, strong Storage `ETag`, RFC3339 `updatedAt`을 read-only 확인한다. bucket/name/owner는 각각 `profile-images`, 요청 exact key, canonicalSub와 일치해야 하며 generation은 key 마지막 UUID와 일치해야 한다. MIME은 `image/jpeg`, `image/png`, `image/webp`, size는 1 byte 이상 5 MiB(`5,242,880`) 이하이며 상한은 성공하고 `5,242,881`부터 413이다. metadata 필드 누락·null·타입 오류·상호 모순은 raw metadata 없이 503이다. storage schema 직접 SQL DML은 금지한다. 멱등 replay를 stale CAS보다 먼저 판정하고, 새 command는 profile row lock 안에서 strong If-Match를 확인한 뒤 exact immutable object와 Storage ETag를 다시 읽어 같은 generation임을 보장하고 object key/source/storage ETag/version, 멱등 응답과 이전 generation cleanup outbox를 원자적으로 기록한다.

성공 `200`은 `profileImageObjectKey`, 파생 `profileImageUrl`, `profileImageSource`, `profileImageVersion`, `updatedAt`을 반환하고 version에 대응하는 strong ETag를 응답한다. storage public URL은 `SUPABASE_URL + /storage/v1/object/public/profile-images/ + immutable canonical key`로 파생하며 immutable generation 자체가 cache identity다. 계약 fixture의 canonical Supabase origin은 `https://project.example.invalid`이고 validator는 이 origin과 exact path를 사용한다. provider fallback URL은 변경하지 않는다. DB에는 전체 public URL, signed URL, provider token 또는 환경별 host를 저장하지 않는다.

명시적 `{"profileImageObjectKey":null}`은 storage 선택을 해제한다. 응답은 유효한 HTTPS provider image를 `google`, `kakao`, `custom:naver` 순서로 선택해 source `provider`로 반환하고, 후보가 없으면 URL null/source `none`이다. legacy `profile_image_url`은 #78 migration 동안 provider fallback read compatibility로만 취급하고 신규 writer는 Storage URL을 쓰지 않는다.

Idempotency scope는 canonicalSub + method/path + key이며 TTL은 24시간이다. 동일 key/body/original If-Match replay는 stale 검사를 다시 적용하지 않고 최초 200 body와 ETag를 반환한다. 같은 key의 다른 body는 `IDEMPOTENCY_PAYLOAD_CONFLICT`, 처리 중은 `IDEMPOTENCY_REQUEST_IN_PROGRESS`, 새 command의 stale If-Match는 `PROFILE_IMAGE_VERSION_CONFLICT` 409다. 새 command는 profile row lock 안에서 If-Match를 비교한다.

교체/clear가 커밋되면 이전 immutable object의 exact key+Storage ETag를 cleanup outbox에 기록한다. worker는 outbox claim 뒤 profile row를 잠그고 DB current reference가 그 key를 가리키지 않는지, owner/key/generation/Storage ETag가 enqueue 시점과 같은지 다시 확인한 경우에만 Storage API로 삭제한다. unique `(object_key, storage_etag)`와 claim/status/attempt/next-attempt가 중복 실행을 막고 새 generation과의 interleaving이 새 객체를 대상으로 바꾸지 못하게 한다. 첫 upload 후 확정 실패 orphan도 grace cutoff 이전이며 어떤 profile도 참조하지 않는 immutable generation에 한해 owner/key/generation/ETag를 재확인한다. 검증, metadata 조회, stale/version·멱등 충돌 실패는 profile을 변경하거나 cleanup을 enqueue하지 않는다. 탈퇴는 #106이 Auth 삭제 전에 canonical prefix를 list하고 owner_id=canonicalSub 객체만 Storage API로 제거한다.

#78의 미래 additive migration은 nullable `user_profiles.profile_image_object_key`, nullable `profile_image_storage_etag`, `profile_image_source text not null`, `profile_image_version bigint not null default 0`을 추가한다. 기존 row는 provider URL이 있으면 source `provider`, 없으면 `none`으로 backfill하고 key/ETag는 null, version은 0이다. version 범위는 `0..9223372036854775807`이고 commit마다 정확히 1 증가하며 상한 overflow는 mutation 없이 409다. source `storage`는 key와 Storage ETag가 모두 non-null, source `provider|none`은 둘 다 null이어야 한다.

기존 `20260810000000_api_idempotency_registry.sql`의 `api_idempotency_records`를 재정의하지 않는다. `(owner_sub,http_method,normalized_path,idempotency_key)` PK, `attempt_token`, `PROCESSING|COMPLETED`, response header/body bytea와 lease/completed/expiry lifecycle을 그대로 재사용한다. canonical request hash는 body+original If-Match를 묶고 완료 snapshot은 최초 status, Content-Type/ETag header bytes와 body bytes를 보존한다. cleanup outbox의 exact fields는 `id`, `owner_user_id`, `object_key`, `storage_etag`, `source_profile_version`, `reason`, `status`, `claim_token`, `claimed_at`, `attempt_count`, `next_attempt_at`, `completed_at`, `created_at`이다. reason은 `replacement|clear|orphan|account_deletion`, lifecycle은 `pending|claimed|succeeded|retry`이며 `(object_key, storage_etag)`가 unique fence다. 이 Issue는 계약/문서만 확정하며 실제 migration과 Java/Storage 구현은 #78 소유다.

오류는 원인·Storage metadata·다른 owner 객체 존재를 노출하지 않는 8-field Problem Details다. request/key/header는 400, 인증은 401, missing/wrong bucket/name/owner mismatch는 동일 404, version/idempotency는 409, size 초과는 413, MIME은 415, malformed/unavailable metadata는 503이다.

`fixtures/contracts/profile-legal/storage-metadata.json`은 JPEG 1 byte, PNG exact 5 MiB, WebP 성공과 exact 5 MiB+1, unsupported MIME, missing/wrong bucket/name/owner, metadata missing/null/malformed, zero size, RFC3339 오류, generation/ETag 불일치를 deterministic read-only Storage stub으로 고정한다. request/success fixture는 explicit null clear, provider/none fallback, initial/confirm/clear/replay/stale의 exact ETag/version/header를 고정한다.

DELETE는 공통 #72의 command-like `apply` 연산으로 `202` deletion request를 만든다. scope는 canonical JWT sub + method/path + Idempotency-Key이고, 같은 canonical body replay는 replay cutoff 전 최초 `deletionRequestId`와 동일 status token을 돌려준다. nonterminal cutoff는 token expiry, terminal cutoff는 `min(token expiry, terminalAt + 24h)`이며 equality부터 replay하지 않는다. cutoff에서는 status-token ciphertext/keyVersion만 삭제하고 비가역 verifier hash는 expiry 뒤 24시간까지 보존해 `expiresAt <= now < verifier cutoff`를 410으로 판별한다. verifier cutoff equality부터 hash를 삭제해 invalid 401로 처리한다. worker의 encrypted auth subject는 이 token cleanup과 분리해 nonterminal expiry와 late retry 동안 보존하고, Auth 삭제 성공 또는 미래 Auth retry가 없음을 보장하는 safe terminalization에서만 같은 committed transition으로 제거한다. 평문 저장·로그를 금지하며 keyVersion 기반 rotation 실패는 raw cause 없이 503 fail-closed다.

삭제 상태는 `queued/running/succeeded/failed/cancelled` discriminator만 허용한다. 각 상태의 `currentStep`, `nextRetryAt`, `completedAt` presence/nullability는 closed schema와 fixture로 고정하며 PII와 provider 오류는 노출하지 않는다. endpoint별 error matrix는 global Problem Details code/status/condition/example과 양방향으로 일치해야 한다.

삭제 상태 capability 인증은 verifier hash를 먼저 찾고 constant-time 비교하며 hash row가 없을 때도 dummy 비교한다. missing/malformed/unknown token은 ID 존재 여부와 무관하게 401, 검증된 token과 path ID 불일치 또는 missing ID는 동일 403, token이 해당 ID를 증명한 뒤 status row만 없을 때 404다. 따라서 검증되지 않은 path ID의 존재성을 응답으로 추론할 수 없다.

프로필의 공개 `providers`는 `google`, `kakao`, `custom:naver`만 허용한다. 저장값을 trim 후 ASCII lowercase로 정규화하고 정규화 뒤 중복을 제거한 다음 이 canonical 순서로 투영한다. `email` identity는 공개 provider가 아니므로 제외하며, email-only 사용자는 닫힌 빈 배열 `providers: []`로 투영한다. 임의 provider 문자열은 공개 응답에 포함하지 않는다.

법정 문서는 `(type, requestedLocale)`별로 하나의 서버 평가 시각에서 `effectiveAt <= evaluatedAt`인 후보를 선택한다. 해당 type에 요청 locale 후보가 하나라도 있으면 그 locale만 사용하고, 없을 때만 `ko-KR`로 fallback한다. 정렬은 `effectiveAt DESC`, semantic version DESC, documentId ASC이며 equality 후보도 eligible하다. 필수 최신 문서의 거부·누락은 `422 LEGAL_CONSENT_REQUIRED`다.

## Readiness

Issue 본문에는 canonical Notion page ID/URL과 Figma file/node 근거가 없으므로 `not-linked`를 유지한다. 공통 readiness 선행 규칙에 따라 metadata, example, 전체 domain implementation은 canonical `{status, evidence}` 구조의 `not-ready`를 유지한다. #181은 계약만 확정하고 Java/Storage/schema 구현은 #78에 남긴다. #18의 프로필 GET/PATCH와 #19의 법정 문서 GET·동의 PUT·DB migration 구현 증거는 endpoint `implementationIssue`·`dbOwner`와 `migrationScope`에 기록하며, #78 이미지 구현, 계정 삭제 API·암호화 #61과 worker #106이 남아 있으므로 전체 implementation readiness를 승격하지 않는다.

#19는 locale별 canonical 법정 문서와 사용자 동의를 위한 append-only migration을 소유한다. `account_deletion_requests` nullable lineage, encrypted token/auth subject와 retention gap은 계속 #61/#106의 후속 append-only migration 범위다.
