# Supabase 소셜 로그인 설정

## 책임과 활성화 상태

Spring의 `GET /api/v1/auth/social/providers`는 프론트엔드가 구현할 수 있는 공급자 **지원 목록이며 실제 Supabase 활성화 상태가 아닙니다**. 반환값은 `id`, `displayName`뿐이며 client id, secret, provider token, 원본 profile을 반환하거나 저장하지 않습니다. `APP_SOCIAL_LOGIN_PROVIDER_IDS`는 이 지원 목록을 제한할 뿐 Supabase Dashboard의 공급자를 켜지 않습니다.

Google과 Kakao의 authorization/code exchange, 세션 발급과 provider secret 보관은 Supabase Auth가 담당합니다. 프론트엔드는 Supabase SDK로 로그인을 시작합니다.

```ts
const SOCIAL_CALLBACK = 'http://127.0.0.1:3000/auth/callback'

await supabase.auth.signInWithOAuth({
  provider: 'google', // 또는 'kakao', 'custom:naver'
  options: { redirectTo: SOCIAL_CALLBACK },
})
```

Spring에는 로그인 시작·callback·redirect API가 없습니다. Redirect URLs의 단일 권위는 Supabase Auth입니다. 로컬에서는 버전 관리되는 `supabase/config.toml`의 `[auth].additional_redirect_urls`, 운영에서는 Supabase Dashboard의 Authentication → URL Configuration만 사용합니다. 프론트엔드는 사용자 입력 `next`, query, fragment나 외부 Origin으로 `redirectTo`를 조립하지 않고 빌드별 고정 callback만 사용합니다. 등록되지 않은 redirect는 Supabase Auth가 거부하며, 저장소 정책 테스트는 로컬 목록에 wildcard·query·fragment가 없음을 검사합니다. Supabase smoke는 비활성 OAuth provider의 오류를 성공으로 간주하지 않고 실제 Auth email-link 생성 경로에서 등록된 callback이 보존되고 악성 URL이 site URL로 fail-closed되는지 대조합니다.

Spring에서 공개하는 경로는 다음 두 GET뿐입니다.

- `GET /api/v1/auth/social/providers`
- `GET /api/v1/auth/social/naver/userinfo`

다른 `/api/v1/auth/social/**` 경로와 POST 등 다른 method는 Supabase JWT 인증 체인으로 전달되어 공개되지 않습니다.

## 환경변수

| 변수 | 용도 | 기본값 |
| --- | --- | --- |
| `APP_SOCIAL_LOGIN_PROVIDER_IDS` | 프론트에 공개할 지원 목록 | `google,kakao,custom:naver` |

Google·Kakao·Naver client secret은 Spring과 Compose에 주입하지 않습니다. 로컬 OAuth 공급자는 기본 비활성화 상태이며, credential이 없는 `supabase start`와 smoke test도 안전하게 기동합니다. 실제 공급자 활성화와 secret 입력은 Supabase Dashboard 또는 별도 비밀 관리 절차에서만 수행합니다.

## Google 설정 체크리스트

1. Google Cloud Console에서 OAuth consent screen과 Web application OAuth client를 만듭니다.
2. 승인 redirect URI에 `https://<project-ref>.supabase.co/auth/v1/callback`을 정확히 등록합니다.
3. Supabase Dashboard의 Authentication → Providers → Google에서 공급자를 활성화하고 client id/secret을 입력합니다.
4. Authentication → URL Configuration의 Redirect URLs에 프론트 callback을 정확히 등록합니다.
5. 프론트엔드는 고정 `provider: 'google'`과 등록된 callback만 사용합니다.

로컬 `supabase/config.toml`에는 Google credential 참조가 없으므로 provider catalog에 Google이 보여도 로컬 Google 로그인이 활성화됐다는 뜻이 아닙니다. 로컬 OAuth E2E가 필요하면 별도 보안 작업에서 공식 Supabase 로컬 provider 설정을 추가하고 secret은 환경변수로만 주입합니다.

## Kakao 설정 체크리스트

1. Kakao Developers에서 Kakao Login과 필요한 동의 항목을 설정합니다.
2. Redirect URI에 `https://<project-ref>.supabase.co/auth/v1/callback`을 정확히 등록합니다.
3. Supabase Dashboard의 Authentication → Providers → Kakao에서 REST API key와 client secret을 입력합니다.
4. Supabase Redirect URLs에 프론트 callback을 정확히 등록합니다.
5. 프론트엔드는 고정 `provider: 'kakao'`만 사용하고 Kakao secret을 Spring에 보내지 않습니다.

Kakao도 로컬 `supabase/config.toml`에서 기본 비활성화이며 catalog는 지원 계약만 나타냅니다.

## Naver UserInfo adapter

`GET /api/v1/auth/social/naver/userinfo`는 브라우저 로그인 API가 아니라 Supabase Auth custom provider가 back-channel에서 사용하는 adapter입니다. `Authorization: Bearer <naver-provider-access-token>`만 받으며 token은 Naver 공식 최대 길이 256자와 RFC 6750 `b64token` 문자 집합으로 제한합니다. 누락·중복·공백·control 문자·comma·colon·semicolon·query/form token은 Naver를 호출하기 전에 401로 거부합니다.

adapter는 코드에 고정된 `https://openapi.naver.com/v1/nid/me`만 호출하고 redirect를 따르지 않습니다. 요청자가 URL이나 host를 지정할 수 없어 SSRF 경로가 없습니다. HTTP body는 최대 64 KiB이며 연결은 2초, 요청 시작부터 header와 전체 body 완료까지는 3초 deadline으로 제한합니다. 200 header 또는 일부 body가 먼저 도착해도 deadline을 연장하지 않으며 초과·취소 시 HTTP subscription을 닫습니다. 별도 executor를 만들지 않아 요청별 thread나 shutdown 책임을 남기지 않습니다. 애플리케이션 인스턴스마다 초당 60건의 고정 window와 동시 outbound 8건의 bulkhead를 적용합니다. 요청량 초과는 429, 동시 처리 상한 초과는 503이며 timeout을 포함해 호출이 끝나면 permit을 반드시 반환합니다.

Naver 성공 envelope는 `resultcode`가 문자열 `00`, `message`가 문자열 `success`인지 정확히 확인합니다. 누락·타입 오류·다른 값·malformed body는 표준 UserInfo로 변환하지 않습니다. nested `response.id`, `response.email`, `response.name`, `response.nickname`, `response.profile_image`만 `sub`, `email`, `name`, `preferred_username`, `picture`로 평탄화합니다. email이 없으면 `422 SOCIAL_NAVER_EMAIL_REQUIRED`이며 임의 email을 만들지 않습니다.

Naver는 이메일 검증 여부를 제공하지 않습니다. 따라서 adapter는 `email_verified`를 반환하지 않고 검증된 이메일로 신뢰 승격하지 않습니다. Supabase Auth는 같은 이메일을 기준으로 자동 identity 연결을 수행할 수 있으므로, 기존 계정이 있는 운영 프로젝트에서 custom Naver provider를 활성화하기 전에 미검증 이메일 처리와 계정 연결/탈취 시나리오를 격리 프로젝트에서 반드시 검증해야 합니다.

## Naver OAuth·PKCE 호환성 경계

2026-07-29 현재 Naver 공식 API 명세는 `https://nid.naver.com/oauth2.0/authorize`, `https://nid.naver.com/oauth2.0/token`, 필수 `state`를 문서화합니다. 공식 설명상 scope는 전송할 필요 없음이며 OIDC discovery, `code_challenge`, `code_verifier` 지원을 명시하지 않습니다. 반면 Supabase custom OAuth provider는 PKCE를 기본 활성화합니다.

따라서 legacy Naver endpoint가 PKCE를 지원한다고 단정하지 않습니다. 실제 Naver 애플리케이션과 격리 Supabase 프로젝트에서 authorize/token 교환, email 미제공, identity 연결을 확인하기 전에는 `custom:naver`를 운영에서 활성화하지 않습니다. 실제 호환성 검증 전에는 운영에서 활성화하지 않습니다. 호환되지 않는다는 이유만으로 PKCE를 임의로 끄지 않으며, Naver가 공식 OIDC/PKCE endpoint와 scope를 제공하면 공식 discovery·scope 계약으로 전환한 뒤 별도 보안 리뷰를 받습니다.

현재 코드와 catalog는 Naver UserInfo 변환 경계를 준비한 상태이며 운영 Naver 로그인 완료를 주장하지 않습니다. 수동 검증 시에는 다음을 확인합니다.

1. Naver callback에 Supabase가 표시하는 `https://<project-ref>.supabase.co/auth/v1/callback`을 등록합니다.
2. 격리 Supabase 프로젝트에서 `custom:naver` manual OAuth2 provider를 만들고 client id/secret은 Dashboard에만 입력합니다.
3. UserInfo URL은 외부에서 접근 가능한 `https://<spring-api-host>/api/v1/auth/social/naver/userinfo`로 지정합니다.
4. Supabase 기본 PKCE를 유지한 실제 authorize/token 흐름이 성공하는지 확인합니다. 성공하지 않으면 운영 활성화를 중단합니다.
5. email 제공 동의를 필수로 요청하되, email이 검증됐다고 간주하지 않습니다.
6. 신규/기존 동일 이메일 사용자의 자동 identity 연결 결과를 확인하고 기대와 다르면 운영 활성화를 중단합니다.

원본 token, Naver body, provider 오류 메시지, 이메일·휴대전화·생년월일·성별·연령대는 로그·오류·DB에 남기지 않습니다. 오류 응답은 공통 `application/problem+json` writer를 사용해 `type`, `title`, `status`, 고정 한국어 `detail`, occurrence URI `instance`, 분류된 `code`, 요청 단위 `traceId`, 빈 `fieldErrors` 정확히 8개 필드를 제공합니다. `instance`는 `urn:timing-jeju:problem:<traceId>`이며 `X-Trace-Id` 헤더와 body에는 같은 32자리 소문자 hex 값을 사용합니다. `message` 필드와 query string은 공개하지 않습니다.

## 공식 근거

- [Supabase Social Login](https://supabase.com/docs/guides/auth/social-login)
- [Supabase Custom OAuth/OIDC Providers](https://supabase.com/docs/guides/auth/custom-oauth-providers)
- [Supabase Redirect URLs](https://supabase.com/docs/guides/auth/redirect-urls)
- [Supabase Identity Linking](https://supabase.com/docs/guides/auth/auth-identity-linking)
- [Supabase Google local provider 설정](https://supabase.com/docs/guides/auth/social-login/auth-google)
- [Naver 로그인 API 명세](https://developers.naver.com/docs/login/api/api.md)
- [Naver 사용자 프로필 선택 제공 공지](https://developers.naver.com/notice/article/7684)

## 검증

```bash
cd services/spring-api
./gradlew clean check
cd ../..
./scripts/quality-gate.sh
./scripts/supabase-smoke-test.sh
./scripts/docker-smoke-test.sh
```

로컬 Supabase smoke는 credential 없는 Auth 기동, reset 2회와 실제 ES256 access token의 Spring JWT 검증 회귀만 확인합니다. Google·Kakao·Naver 실제 공급자 E2E는 콘솔 credential과 사용자 동의가 필요한 별도 수동 검증 범위입니다.
