# Timing Jeju Spring 백엔드

제주 여행 동선과 관광·교통 데이터를 제공하는 Timing Jeju의 Spring Boot 백엔드 저장소입니다. 공개 REST API, 인증·인가, DB·외부 API 연동, MCP 호출 조립과 결과 저장을 담당합니다.

일정 계산과 AI 기능을 담당하는 FastAPI MCP는 별도 [Timing-Jeju/jeju_AI](https://github.com/Timing-Jeju/jeju_AI) 저장소에서 개발·검증·배포합니다. 두 저장소는 런타임과 CI를 공유하지 않고 문서화된 MCP 계약으로만 연동합니다.

## 저장소 구조

```text
.
├── services/
│   └── spring-api/       # 공개 API, DB·외부 API, MCP 조립과 결과 저장
├── docs/                 # Spring 아키텍처와 서비스 간 연동 계약
├── supabase/             # 단일 public 스키마 마이그레이션과 로컬 Supabase 설정
├── db/                   # 일반 PostgreSQL 전용 호환 계층과 검증 쿼리
├── fixtures/             # 계약·DB 검증 fixture
├── scripts/              # Spring 품질 게이트와 자동화
├── compose.yml           # Spring API 통합 실행
└── AGENTS.md             # 공통 개발·Git·보안 규칙
```

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- springdoc-openapi 3.0.3, Swagger UI
- Gradle 9.5.1 Wrapper
- PostgreSQL 16 + PostGIS 3.4
- Supabase CLI 2.110.0 + 로컬 PostgreSQL 17/PostGIS
- JUnit 5, AssertJ, Mockito, Spring Test, ArchUnit, JaCoCo
- Docker / Docker Compose

## 요구 환경

- JDK 21
- Docker Engine과 Docker Compose
- Supabase CLI 2.110.0
- Git, GitHub CLI(원격 설정 또는 PR 생성 시)

## 시작하기

```bash
cp .env.example .env
./scripts/install-git-hooks.sh
docker compose -f docker-compose.yml up -d
cd services/spring-api
./gradlew bootRun
```

Windows PowerShell에서는 다음을 실행합니다.

```powershell
Copy-Item .env.example .env
./scripts/install-git-hooks.ps1
docker compose -f docker-compose.yml up -d
Set-Location services/spring-api
./gradlew.bat bootRun
```

애플리케이션 상태는 `http://localhost:8080/actuator/health`에서 확인합니다. `.env`의 예시 비밀번호는 로컬 전용이며 실제 운영 비밀값을 저장소에 넣지 않습니다.

프론트엔드 협업용 Swagger UI는 `http://localhost:8080/swagger-ui/index.html`, OpenAPI JSON은 `http://localhost:8080/v3/api-docs`에서 확인합니다. 문서에는 `/api/v1/**` 공개 API만 포함합니다.

## 테스트와 품질 게이트

```bash
cd services/spring-api
./gradlew clean check
./gradlew unitTest sliceTest integrationTest architectureTest
./gradlew openApiDocs
cd ../..
./scripts/quality-gate.sh
```

루트 품질 게이트는 브랜치·비밀정보·백엔드 구조·Spring 검사와 Docker smoke test를 검증합니다. FastAPI의 uv·Ruff·mypy·pytest 검사는 `jeju_AI` 저장소의 독립 CI에서 수행합니다.

## Docker 실행

```bash
docker compose -f compose.yml up --build
./scripts/docker-smoke-test.sh
```

기존 `docker-compose.yml`은 PostgreSQL/PostGIS 단독 개발용으로 유지합니다. `compose.yml`은 Spring 애플리케이션과 DB를 함께 실행하고, `compose.test.yml`은 격리된 smoke test에 사용합니다. FastAPI는 배포 환경의 private network에서 연결하며 이 저장소의 Compose가 구현 컨테이너를 소유하지 않습니다.

## Supabase 로컬 환경

로컬 개발은 로컬 Supabase Auth·PostgreSQL/PostGIS를, 운영은 호스팅된 Supabase Auth·PostgreSQL/PostGIS를 사용합니다. public 애플리케이션 스키마는 Flyway 없이 `supabase/migrations`만으로 관리합니다.

```bash
supabase start
supabase db reset
./scripts/supabase-smoke-test.sh
```

환경별 연결, 마이그레이션 소유권, 테스트 사용자 준비와 초기화 주의사항은 [데이터베이스 개발 환경](db/README.md)을 따릅니다.
Spring의 Supabase access token 검증, JWKS/로컬 legacy HS256 분리와 CORS 환경변수는 [인증·인가 설정](docs/AUTHENTICATION.md)을 따릅니다.

## 개발 프로세스

PM 세션 → Issue → 최신 `develop` 기반 작업 브랜치 → TDD → 로컬 품질 게이트 → PR 전 Reviewer 세션 → 승인 → PR → GitHub CI/공식 리뷰 → `develop` 머지 순서입니다. Release Issue만 `develop`에서 `main`으로 출시합니다.

## 문서

- [아키텍처](docs/ARCHITECTURE.md)
- [데이터베이스 개발 환경](db/README.md)
- [API 문서화 규칙](docs/API_DOCUMENTATION.md)
- [Spring-FastAPI 내부 연동 명세](docs/designs/timing-jeju-spring-fastapi-integration-contract.md)
- [FastAPI MCP 구현 계약](https://github.com/Timing-Jeju/jeju_AI/blob/develop/docs/FASTAPI_MCP_CONTRACT.md)
- [Git 및 출시 흐름](docs/GIT_WORKFLOW.md)
- [TDD 가이드](docs/TDD_GUIDE.md)
- [PR 전·후 코드리뷰](docs/CODE_REVIEW.md)
- [완료 정의](docs/DEFINITION_OF_DONE.md)
- [GitHub 설정](docs/GITHUB_SETTINGS.md)
- [기여 가이드](CONTRIBUTING.md)
- [보안 정책](SECURITY.md)
