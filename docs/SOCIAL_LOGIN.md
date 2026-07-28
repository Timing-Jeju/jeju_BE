# Supabase 소셜 로그인 설정

## 책임 경계

프론트엔드는 공개 API `GET /api/v1/auth/social/providers`에서 `id`, `displayName`만 받아 로그인 버튼을 구성합니다. 이 값 이외의 client id, client secret, access token, refresh token, provider 원본 profile은 반환하거나 저장하지 않습니다.

Google과 Kakao 로그인은 프론트엔드가 Supabase SDK로 시작합니다.

```ts
await supabase.auth.signInWithOAuth({
  provider: 'google', // 또는 'kakao', 'custom:naver'
  options: { redirectTo: 'http://127.0.0.1:3000/auth/callback' },
})
```

`redirectTo`는 사용자 입력 `next` 값으로 만들지 말고, `APP_SOCIAL_LOGIN_REDIRECT_URLS` 및 Supabase Auth의 Redirect URLs에 등록된 정확한 URL만 사용합니다. wildcard, query/fragment가 있는 URL, 임의의 외부 URL을 redirect allowlist에 넣지 않습니다.

Spring의 `GET /api/v1/auth/social/naver/userinfo`는 브라우저 로그인 API가 아닙니다. Supabase Auth `custom:naver`가 back-channel에서 Naver provider access token을 `Authorization: Bearer` 헤더로 전달할 때만 사용합니다. 이 endpoint는 Supabase JWT 검증 대상이 아니며, 고정된 `https://openapi.naver.com/v1/nid/me`만 호출합니다. 요청자가 URL·host·redirect를 지정할 수 없으므로 SSRF 경로가 없습니다.

## 환경변수

| 변수 | 용도 | 예시 |
| --- | --- | --- |
| `APP_SOCIAL_LOGIN_ENABLED_PROVIDER_IDS` | 공개할 고정 provider 목록 | `google,kakao,custom:naver` |
| `APP_SOCIAL_LOGIN_REDIRECT_URLS` | Spring 시작 시 검증할 프론트 redirect allowlist | `http://127.0.0.1:3000/auth/callback` |
| `SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID` | 로컬 Supabase CLI Google 시험용 client id | 실제 값은 미추적 `.env` |
| `SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_SECRET` | 로컬 Supabase CLI Google 시험용 secret | 실제 값은 미추적 `.env` |
| `SUPABASE_AUTH_EXTERNAL_KAKAO_CLIENT_ID` | 로컬 Supabase CLI Kakao 시험용 REST API key | 실제 값은 미추적 `.env` |
| `SUPABASE_AUTH_EXTERNAL_KAKAO_CLIENT_SECRET` | 로컬 Supabase CLI Kakao secret | 실제 값은 미추적 `.env` |

`NAVER_OAUTH_CLIENT_ID`, `NAVER_OAUTH_CLIENT_SECRET`은 `.env.example`에 이름만 제공하지만 Spring과 Compose는 읽지 않습니다. Supabase Dashboard의 custom provider 설정에만 입력합니다. 운영 값은 Supabase Dashboard 또는 배포 플랫폼의 Secret 기능으로 관리하고 Git, 로그, Swagger, fixture에 넣지 않습니다.

## Google 설정 체크리스트

1. Google Cloud Console에서 OAuth consent screen과 Web application OAuth client를 생성합니다.
2. 승인 redirect URI에 `https://<project-ref>.supabase.co/auth/v1/callback`을 정확히 등록합니다. 로컬 CLI 시험은 `http://127.0.0.1:54321/auth/v1/callback`을 등록합니다.
3. Supabase Dashboard의 Authentication → Providers → Google에서 provider를 활성화하고 Google client id/secret을 입력합니다.
4. Supabase Authentication → URL Configuration의 Site URL 및 Redirect URLs에 프론트 callback URL을 정확히 등록합니다.
5. 프론트엔드는 `provider: 'google'` 및 등록된 `redirectTo`만 사용합니다.

## Kakao 설정 체크리스트

1. Kakao Developers에서 Kakao Login을 활성화하고 필요한 동의 항목을 설정합니다.
2. Redirect URI에 `https://<project-ref>.supabase.co/auth/v1/callback`을 정확히 등록합니다. 로컬 CLI 시험은 `http://127.0.0.1:54321/auth/v1/callback`을 등록합니다.
3. Supabase Dashboard의 Authentication → Providers → Kakao에서 Kakao REST API key(client id)와 client secret을 입력합니다.
4. Supabase의 Site URL/Redirect URLs와 Spring `APP_SOCIAL_LOGIN_REDIRECT_URLS`가 같은 프론트 callback URL을 가리키는지 확인합니다.
5. 프론트엔드는 `provider: 'kakao'`로만 OAuth를 시작하며 Spring에 Kakao secret을 보내지 않습니다.

## Naver custom OAuth 설정 체크리스트

1. Naver Developers에서 네아로(Client ID/Secret)를 만들고 이메일을 필수 동의 항목으로 설정합니다. name, nickname, profile image는 선택적 표준 UserInfo 필드입니다.
2. Naver callback URL에 `https://<project-ref>.supabase.co/auth/v1/callback`을 등록합니다. 로컬 CLI는 `http://127.0.0.1:54321/auth/v1/callback`을 사용합니다.
3. Supabase Dashboard의 Authentication → Providers → Add provider에서 ID를 **`custom:naver`**로 만들고 OAuth 2.0을 선택합니다.
4. Authorization URL은 `https://nid.naver.com/oauth2.0/authorize`, Token URL은 `https://nid.naver.com/oauth2.0/token`으로 입력합니다.
5. UserInfo URL은 배포된 Spring API의 `https://<spring-api-public-host>/api/v1/auth/social/naver/userinfo`로 입력합니다. Supabase Cloud가 호출하므로 localhost나 사설망 주소를 넣으면 안 됩니다.
6. Naver client id/secret은 이 custom provider 화면에만 입력합니다. PKCE는 기본 활성 상태를 유지하고 비활성화하지 않습니다. `email_optional`은 활성화하지 않습니다.
7. 프론트엔드는 `provider: 'custom:naver'`와 등록된 고정 `redirectTo`만 사용합니다.

Naver adapter는 nested `response.id`, `response.email`, `response.name`, `response.nickname`, `response.profile_image`를 표준 `sub`, `email`, `email_verified`, `name`, `preferred_username`, `picture`로 변환합니다. email이 없으면 `422 SOCIAL_NAVER_EMAIL_REQUIRED`을 반환하며 임의 email을 만들지 않습니다. 401·403·429·5xx·timeout·malformed·64 KiB 초과 응답은 분류된 `code`, 한국어 `message`, `traceId`만 반환합니다. raw token, Naver 원본 body, provider error message와 개인정보는 로그·응답·저장소에 남기지 않습니다.

Naver는 [네아로 API 문서](https://developers.naver.com/docs/login/api/api.md), Google/Kakao는 [Supabase Social Login](https://supabase.com/docs/guides/auth/social-login), custom provider는 [Supabase Custom OAuth Providers](https://supabase.com/docs/guides/auth/custom-oauth-providers), redirect와 PKCE/email 기본값은 [Supabase Auth 설정](https://supabase.com/docs/guides/local-development/customizing-your-local-development-setup) 기준으로 확인합니다.

## 검증

```bash
cd services/spring-api
./gradlew clean check
cd ../..
./scripts/quality-gate.sh
./scripts/supabase-smoke-test.sh
./scripts/docker-smoke-test.sh
```

로컬 Supabase smoke는 실제 ES256 access token의 Spring JWT 검증 회귀를 확인합니다. 외부 OAuth provider의 실제 콘솔 자격증명·사용자 동의 화면은 비밀값과 공개 callback 주소가 필요한 별도 수동 검증 범위입니다.
