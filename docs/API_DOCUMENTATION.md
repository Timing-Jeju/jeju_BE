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
