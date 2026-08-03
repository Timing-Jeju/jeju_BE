# API 문서화 규칙

## 목적

프론트엔드가 Swagger UI에서 API를 탐색하고 OpenAPI JSON으로 타입과 클라이언트를 생성할 수 있도록 `springdoc-openapi`를 사용합니다. 문서 때문에 Controller 구현이 복잡해지지 않도록 문서 책임을 분리합니다.

## 접근 주소

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`
- CI 공유 파일: `services/spring-api/build/openapi/openapi.json`

문서에는 공개 API인 `/api/v1/**`만 포함합니다. Actuator와 내부 관리 경로는 Swagger에 노출하지 않습니다.

## 애노테이션 최소화 원칙

Spring MVC의 경로, HTTP 메서드, Request DTO, Response DTO와 Jakarta Validation 정보는 springdoc이 자동으로 추론하게 둡니다. Controller 구현에는 설명을 위한 Swagger 애노테이션을 직접 쌓지 않습니다.

추가 설명이 필요한 경우 도메인의 Controller 옆에 문서 계약 인터페이스를 둡니다.

```text
domain/place/
├── controller/
│   ├── PlaceController.java
│   └── docs/
│       └── PlaceApiDocs.java
├── dto/request/
└── dto/response/
```

`PlaceController`는 `PlaceApiDocs`를 구현하고, `@Operation`, API별 성공 응답과 특별한 오류 응답은 문서 계약 인터페이스에 작성합니다. Controller 구현은 요청 위임과 응답 반환에만 집중합니다.

## 공통 문서 책임

- API 제목·버전·설명은 `OpenApiConfig`에서 관리합니다.
- 인증 방식과 공통 오류 응답은 `OpenApiCustomizer`에서 한 번만 등록합니다.
- DTO의 `@Schema`는 이름만으로 의미나 예시를 알 수 없는 필드에만 사용합니다.
- 반복되는 응답 예시를 Controller마다 복사하지 않습니다.
- 실제 API 동작은 Controller 테스트로 검증하고 OpenAPI JSON 생성 테스트를 함께 통과시킵니다.

공통 오류 schema는 `ApiProblemDetails` 하나를 사용합니다. Content-Type은 `application/problem+json`이며 core 필드 `type`, `title`, `status`, `detail`, `instance`와 확장 필드 `code`, `traceId`, `fieldErrors` 정확히 8개를 모두 필수로 문서화합니다. `message`를 비롯한 추가 envelope 필드는 만들지 않습니다. `traceId`는 서버가 생성한 32자리 소문자 hex이고 `instance`는 같은 값을 사용한 occurrence URI `urn:timing-jeju:problem:<traceId>`입니다. 공통 500은 모든 operation에, 공통 401/403은 전역 Bearer 인증을 상속하는 operation에 reusable response 참조로 연결합니다. API별 오류 응답은 이 공통 schema를 참조하고 도메인별 중복 오류 DTO를 만들지 않습니다.

모든 공개 API 응답은 서버가 생성한 32자리 소문자 hex `X-Trace-Id` 헤더를 필수로 반환합니다. Problem Details 응답에서는 헤더 값, body의 `traceId`, `instance`의 `<traceId>`가 모두 같아야 합니다. OpenAPI는 reusable `TraceId` header component를 등록하고 각 operation의 모든 응답이 `#/components/headers/TraceId`를 참조하게 합니다. 클라이언트가 보낸 `X-Trace-Id`는 신뢰하거나 재사용하지 않습니다.

소셜 로그인 지원 카탈로그와 Naver Custom OAuth UserInfo adapter의 정확한 두 GET만 공개 endpoint입니다. 전역 `bearerAuth`는 Supabase access token용이므로 두 operation에는 빈 `security` 배열을 명시합니다. Naver adapter의 `Authorization` 헤더는 Supabase JWT가 아닌 Supabase Auth가 back-channel로 전달하는 Naver provider access token이며, API 문서 계약 인터페이스에서만 설명합니다. Naver가 이메일 검증 여부를 제공하지 않으므로 성공 schema에는 `email_verified`가 없습니다. 애플리케이션 rate limit 429와 bulkhead·가용성 503도 오류 계약에 포함합니다.

## 운영 보안

개발 환경에서는 Swagger UI와 OpenAPI JSON을 활성화합니다. 외부 공개가 필요하지 않은 운영 환경에서는 다음 환경변수로 비활성화합니다.

```text
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

## 검증 명령

```bash
cd services/spring-api
./gradlew openApiDocs
./gradlew clean check
```

`openApiDocs`가 생성한 `build/openapi/openapi.json`은 CI Artifact로 프론트엔드에 공유합니다.
