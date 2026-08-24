# 프로필·법정 문서 API 계약

Issue #82의 local contract version은 `1.0.0`이며 공통 REST 계약 #72와 위치정보 정책 #73을 상속한다. 범위는 core 5개와 삭제 상태 extension 1개다. 프로필 GET/PATCH는 #18, 법정 문서 GET과 동의 PUT 및 migration은 #19가 구현하며 계정 삭제 API·암호화는 #61, worker는 #106이 계속 소유한다.

## Endpoint와 owner

| 구분 | Method | Path | Auth | 구현 owner |
| --- | --- | --- | --- | --- |
| core | GET | `/api/v1/me` | required JWT | #18 |
| core | PATCH | `/api/v1/me` | required JWT | #18 |
| core | DELETE | `/api/v1/me` | required JWT + recent reauth + Idempotency-Key | #61 |
| core | GET | `/api/v1/legal-documents` | anonymous 또는 optional Bearer JWT | #19 |
| core | PUT | `/api/v1/me/consents` | required JWT | #19 |
| extension | GET | `/api/v1/account-deletion-requests/{deletionRequestId}` | `X-Deletion-Status-Token` only; JWT는 ownership 근거가 아님 | #61 API, #106 worker |

PATCH는 `nickname`, `locale`만 받으며 omitted와 null을 구분한다. 두 필드의 omitted는 기존 값을 보존하고 null은 거부한다. `email`, `providers`, provider `profileImageUrl`은 read-only이며 이미지 업로드·변경은 #78이 소유한다. 커밋 fixture의 Bearer 값은 secret scanner가 허용하는 `Bearer <fixture-access-token>`만 사용하고, validator가 이 exact placeholder만 실제 wire grammar 검증용 생성값으로 치환한다.

DELETE는 공통 #72의 command-like `apply` 연산으로 `202` deletion request를 만든다. scope는 canonical JWT sub + method/path + Idempotency-Key이고, 같은 canonical body replay는 replay cutoff 전 최초 `deletionRequestId`와 동일 status token을 돌려준다. nonterminal cutoff는 token expiry, terminal cutoff는 `min(token expiry, terminalAt + 24h)`이며 equality부터 replay하지 않는다. cutoff에서는 status-token ciphertext/keyVersion만 삭제하고 비가역 verifier hash는 expiry 뒤 24시간까지 보존해 `expiresAt <= now < verifier cutoff`를 410으로 판별한다. verifier cutoff equality부터 hash를 삭제해 invalid 401로 처리한다. worker의 encrypted auth subject는 이 token cleanup과 분리해 nonterminal expiry와 late retry 동안 보존하고, Auth 삭제 성공 또는 미래 Auth retry가 없음을 보장하는 safe terminalization에서만 같은 committed transition으로 제거한다. 평문 저장·로그를 금지하며 keyVersion 기반 rotation 실패는 raw cause 없이 503 fail-closed다.

삭제 상태는 `queued/running/succeeded/failed/cancelled` discriminator만 허용한다. 각 상태의 `currentStep`, `nextRetryAt`, `completedAt` presence/nullability는 closed schema와 fixture로 고정하며 PII와 provider 오류는 노출하지 않는다. endpoint별 error matrix는 global Problem Details code/status/condition/example과 양방향으로 일치해야 한다.

삭제 상태 capability 인증은 verifier hash를 먼저 찾고 constant-time 비교하며 hash row가 없을 때도 dummy 비교한다. missing/malformed/unknown token은 ID 존재 여부와 무관하게 401, 검증된 token과 path ID 불일치 또는 missing ID는 동일 403, token이 해당 ID를 증명한 뒤 status row만 없을 때 404다. 따라서 검증되지 않은 path ID의 존재성을 응답으로 추론할 수 없다.

프로필의 공개 `providers`는 `google`, `kakao`, `custom:naver`만 허용한다. 저장값을 trim 후 ASCII lowercase로 정규화하고 정규화 뒤 중복을 제거한 다음 이 canonical 순서로 투영한다. `email` identity는 공개 provider가 아니므로 제외하며, email-only 사용자는 닫힌 빈 배열 `providers: []`로 투영한다. 임의 provider 문자열은 공개 응답에 포함하지 않는다.

법정 문서는 `(type, requestedLocale)`별로 하나의 서버 평가 시각에서 `effectiveAt <= evaluatedAt`인 후보를 선택한다. 해당 type에 요청 locale 후보가 하나라도 있으면 그 locale만 사용하고, 없을 때만 `ko-KR`로 fallback한다. 정렬은 `effectiveAt DESC`, semantic version DESC, documentId ASC이며 equality 후보도 eligible하다. 필수 최신 문서의 거부·누락은 `422 LEGAL_CONSENT_REQUIRED`다.

## Readiness

Issue 본문에는 canonical Notion page ID/URL과 Figma file/node 근거가 없으므로 `not-linked`를 유지한다. 공통 readiness 선행 규칙에 따라 metadata, example, 전체 domain implementation은 `not-ready`를 유지한다. 다만 #18의 프로필 GET/PATCH와 #19의 법정 문서 GET·동의 PUT·DB migration은 구현된 owner로 기록한다. 계정 삭제 API·암호화 #61과 worker #106이 남아 있으므로 전체 implementation readiness를 승격하지 않는다.

#19는 locale별 canonical 법정 문서와 사용자 동의를 위한 append-only migration을 소유한다. `account_deletion_requests` nullable lineage, encrypted token/auth subject와 retention gap은 계속 #61/#106의 후속 append-only migration 범위다.
