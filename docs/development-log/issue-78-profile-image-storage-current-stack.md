# Issue #78 프로필 이미지 Storage 현재 스택 재통합

## 2026-09-06 기준과 TDD

- 기준: 원격 `fix/48-trip-place-preferences-current-stack` exact SHA
  `ca38a9eb4c0b1acb60c7e474253627d5cfb33b62`에서 전용 브랜치를 만들었다.
- Red commit `525c736`: migration이 없는 상태에서 bucket, canonical owner key, INSERT-only,
  restrictive anon DB 접근과 append-only compose slot 계약 6건이 실패했다.
- Java Red: profile image API, metadata, CAS, idempotency, cleanup 테스트를 먼저 추가해 신규
  production type 부재로 compile 실패를 확인했다.
- OpenAPI Red: active mode 33 validator가 profile-image 요청·응답 header, example, canonical
  schema와 runtime Problem 정합 28건을 거부했다.
- 공식 Storage 제약 보강 Red: public bucket 설명의 ACL 표현이 기존 fail-closed 검사에
  걸리는 것을 확인하고, 지원되는 bucket 설정/RLS surface만 허용하도록 정적 검사를 추가했다.

## Green과 구현 경계

- `20260913000000_profile_image_storage.sql`은 public bucket을 보정하고 authenticated의
  exact owner immutable-generation INSERT/SELECT만 허용한다. UPDATE/DELETE와 anon
  INSERT/SELECT는 restrictive policy로 profile-images bucket에서 차단한다.
- Storage schema에는 사용자 function/table/index를 만들지 않고 `auth.role()`을 사용하지
  않는다. object upload/delete는 Storage API만 사용하며 upsert는 허용하지 않는다.
- companion GET/PUT은 storage metadata와 byte-exact strong ETag를 확인하고 profile row lock,
  CAS와 replay-before-CAS를 수행한다. 교체·해제는 이전 generation만 cleanup outbox에 넣는다.
- core GET `/me`는 storage, provider, none 순서로 projection하며 PATCH closed schema는
  변경하지 않았다. account deletion reason은 일반 cleanup worker가 claim하지 않아 #106
  전용 서버 삭제 경계를 보존한다.
- Idempotency-Key는 #68 공개 계약과 같이 1~128자 printable ASCII이고 control, non-ASCII,
  129자를 거부한다.
- generated OpenAPI active mode 33, runtime manifest, network-free frontend verifier와 문서를
  함께 갱신했다.

## 검증과 제한

- migration/RLS 정적 계약 7건, profile-legal/OpenAPI 관련 Python 107건을 통과했다.
- profile image focused Java 84건, 전체 unit 1,261건(환경 의존 6건 skip), ArchUnit 36건,
  OpenAPI 문서 11건과 mode 33 validator를 통과했다.
- 외부 datasource, Firebase, MCP와 Supabase 환경변수를 제거한 실행만 사용했고 비밀을
  출력하지 않았다. live Supabase와 운영 DB에는 적용하지 않았다.
- #78의 disposable Testcontainers/Docker 실행은 사용자 승인 범위에서 제외돼 permission
  reviewer가 거부했다. 전체 quality gate와 Docker smoke는 추가 승인 전까지 남아 있다.

## 2026-09-06 Source review 보정

- Red commit `22b65cf`에서 app-owned durable orphan scan cursor가 없는 migration을
  재현했다. 공식 InfoRenderer fixture로 기존 DB row shape parser 실패, literal JSON
  `null`의 service 전 거부, stale UUID OpenAPI assertion도 각각 Red로 고정했다.
- InfoRenderer의 실제 `id/name/version/bucket_id/size/content_type/etag/metadata/last_modified`
  shape와 별도 HEAD strong ETag byte-exact를 검증한다. owner 권위는 사용자 metadata가
  아니라 canonical current subject/key와 authenticated exact-owner immutable INSERT RLS에서
  도출한다.
- app-owned private cursor를 owner/object offset+revision CAS로 보존하고 공식 list v1
  `limit/offset`, `name asc`로 owner/profile/leaf를 bounded page한다. 재시작 뒤 1001개
  앞쪽 객체를 넘는 진행과 삭제로 당겨진 offset의 cycle-wrap 재방문을 DB-free 테스트했다.
- 실제 DB, Testcontainers, Docker, live Supabase와 전체 heavy gate는 승인 범위 밖이라
  실행하지 않았다.
