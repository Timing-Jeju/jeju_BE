# 인증 사용자 프로필 provisioning

Issue #64는 webhook 없이 `GET /me`가 호출하는 `CurrentUserProvisioningService.provision(CurrentUser)`를 canonical writer로 사용한다. 공개 Controller와 profile 응답 구현은 #61이 소유하며, 이 기능은 #82의 프로필·법정 문서 계약을 입력으로 소비한다. #82의 Notion/Figma `not-linked`와 metadata/example/implementation `not-ready` 상태는 이 기능에서 승격하지 않는다.

## 소유권과 identity 읽기

- 검증이 끝난 Supabase JWT의 canonical UUID `sub`만 `user_profiles.id`와 소유권 근거로 사용한다.
- `AuthIdentityReader`는 Supabase 소유 `auth.identities`에서 `provider`, `provider_id`와 `identity_data`의 `email`, `nickname`, `picture`만 SELECT한다.
- `auth.users`와 `auth.identities`에는 운영 DDL이나 INSERT·UPDATE·DELETE를 실행하지 않는다. 일반 PostgreSQL 통합 테스트용 `db/local-postgres/auth_compat.sql`만 격리된 호환 table을 제공한다.
- `raw_user_meta_data`, `raw_app_meta_data`, raw identity JSON, provider token과 임의 subject는 읽기 결과, 로그, metric 또는 public 응답으로 전달하지 않는다.

## 정규화와 멱등성

email identity는 `user_profiles`만 만들고 `social_accounts`를 만들지 않는다. Google과 Kakao는 같은 provider 이름을 사용하며 `custom:naver`만 DB의 `naver`로 변환한다. DB의 `naver`는 #82 공개 provider projection에서 다시 `custom:naver`로 변환한다. 결과 순서는 `google`, `kakao`, `custom:naver`로 고정한다.

`provider_id`는 opaque subject다. null·Unicode blank-only와 512자를 넘는 값만 거부하며, 허용된 값의 ASCII/Unicode leading·trailing whitespace, case와 원래 문자열 순서를 trim·lowercase·재작성하지 않는다. provider 이름의 정규화와 provider subject 보존은 서로 다른 계약이다.

profile과 모든 social account upsert는 한 transaction이다. profile은 canonical `sub`로, social account는 `(user_id, provider)`와 `(provider, provider_user_id)` 제약으로 반복 로그인과 동시 최초 요청을 한 행으로 수렴시킨다. 같은 provider와 subject의 재호출은 표시용 allowlist 값과 마지막 로그인 시각만 갱신하며 `raw_profile`은 항상 빈 JSON object다.

동일 이메일의 다른 `sub`는 `EMAIL_OWNERSHIP_CONFLICT`, 같은 provider subject의 다른 사용자 또는 한 사용자의 다른 subject는 `PROVIDER_SUBJECT_CONFLICT`로 거부한다. 이메일, nickname, metadata를 근거로 자동 병합하지 않는다. 누락되거나 지원하지 않는 provider/provider ID는 저장 전에 `INVALID_AUTH_IDENTITY`로 거부한다. 오류에는 원본 identity JSON이나 DB/provider 예외를 포함하지 않는다.

JDBC 오류 분류는 PostgreSQL `PSQLException`의 SQLSTATE `23505`와 typed constraint metadata를 함께 사용한다. 알려진 email/social unique constraint만 충돌로 변환하고, unknown constraint와 기타 Spring storage failure는 cause가 없는 `STORAGE_UNAVAILABLE`로 닫는다. raw SQL, UUID, provider subject와 DB detail은 application 오류에 연결하지 않는다. null dependency/request 같은 programmer boundary는 storage 오류로 숨기지 않는다.

## 환경과 검증 경계

추가 운영 환경변수, provider secret, service role 전달 또는 FastAPI 연동은 없다. Spring의 기존 DB datasource만 사용한다. 실제 Supabase signup/login 호환성은 로컬 Supabase smoke가 Auth API로 만든 email identity와 실제 JWT를 test-only Spring endpoint에 전달해 profile 1행/social 0행을 검증한다. 운영 코드나 smoke test가 auth table을 직접 쓰지 않는다.

일반 PostgreSQL transaction·동시성은 Testcontainers 통합 테스트에서 검증한다. 동시 최초 요청 테스트는 `user_profiles`의 test-only `BEFORE INSERT` sequence barrier로 두 별도 transaction을 같은 conflicting insert window에 진입시킨 뒤 두 결과가 동일하고 profile/social이 각각 한 행인지 확인한다. provider subject 충돌 테스트는 앞선 profile/social insert까지 같은 transaction에서 rollback되는지 확인한다.
