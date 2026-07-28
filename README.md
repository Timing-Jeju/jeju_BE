# Timing Jeju 백엔드 모노레포

제주 여행 동선과 관광·교통 데이터를 다루는 Timing Jeju의 백엔드 모노레포입니다. 공통 계약과 데이터는 루트에서 관리하고 Spring Boot 공개 API와 FastAPI MCP는 독립된 서비스 디렉터리와 컨테이너로 관리합니다.

## 저장소 구조

```text
.
├── services/
│   ├── spring-api/       # 공개 API, DB·외부 API, MCP 조립과 결과 저장
│   └── fastapi-mcp/      # 내부 계산·AI MCP, 원본 소스 가져오기 전 경계만 정의
├── docs/                 # 공통 아키텍처와 서비스 간 계약
├── db/                   # 공통 PostgreSQL/PostGIS 스키마
├── fixtures/             # 공통 검증 fixture
├── scripts/              # 루트 통합 품질 게이트와 자동화
├── compose.yml           # 서비스 통합 실행
└── AGENTS.md             # 공통 개발·Git·보안 규칙
```

두 서비스는 같은 저장소에서 계약을 함께 변경할 수 있지만 런타임, 의존성, 테스트와 배포 경계는 분리합니다. FastAPI 원본 저장소가 제공되기 전에는 예시 Tool이나 가짜 계산 코드를 만들지 않습니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle 9.5.1 Wrapper
- FastAPI MCP: 소스 가져오기 대기
- PostgreSQL 16 + PostGIS 3.4
- JUnit 5, AssertJ, Mockito, Spring Test, ArchUnit, JaCoCo
- Docker / Docker Compose

## 요구 환경

- JDK 21
- Docker Engine과 Docker Compose
- Python 3.10 이상
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

## 테스트와 품질 게이트

```bash
cd services/spring-api
./gradlew clean check
./gradlew unitTest sliceTest integrationTest architectureTest
cd ../..
./scripts/quality-gate.sh
```

루트 `quality-gate`는 브랜치·비밀정보·모노레포 구조·서비스별 테스트·커버리지·빌드·Docker smoke test를 순서대로 검증합니다. 성공 기록은 커밋되지 않는 `.codex/state/quality-gates/`에 저장됩니다.

GitHub Actions의 `백엔드 모노레포 CI`는 `develop`·`main` 대상 PR과 두 보호 브랜치의 push에서 같은 품질 게이트를 실행합니다. PR 메타데이터와 Gradle Wrapper 무결성을 먼저 확인하고 테스트·JaCoCo 리포트를 14일간 보존합니다. 같은 브랜치에 새 커밋이 올라오면 이전 실행은 자동 취소됩니다.

## Docker 실행

```bash
docker compose -f compose.yml up --build
./scripts/docker-smoke-test.sh
```

기존 `docker-compose.yml`은 PostgreSQL/PostGIS 단독 개발용으로 유지합니다. `compose.yml`은 애플리케이션과 DB를 함께 실행하고, `compose.test.yml`은 격리된 smoke test에 사용합니다.

현재 Compose에는 구현이 존재하는 Spring API만 연결되어 있습니다. FastAPI MCP 원본 소스를 가져오면 private network 서비스와 `/health/live`, `/health/ready` 검증을 추가합니다.

## 개발 프로세스

PM 세션 → Issue → 최신 `develop` 기반 작업 브랜치 → TDD → 로컬 품질 게이트 → PR 전 Reviewer 세션 → 승인 → PR → GitHub CI/공식 리뷰 → `develop` 머지 순서입니다. Release Issue만 `develop`에서 `main`으로 출시합니다.

Codex Skill 사용 예시는 다음과 같습니다.

```text
$pm-issue
요구사항: 관광지 검색 API의 요구사항을 Issue로 정리해 줘.

$tdd-development
Issue: #14

$pre-pr-review
Issue: #14
Base: develop

$create-pr
Issue: #14
Base: develop
```

## 문서

- [아키텍처](docs/ARCHITECTURE.md)
- [Git 및 출시 흐름](docs/GIT_WORKFLOW.md)
- [TDD 가이드](docs/TDD_GUIDE.md)
- [PR 전·후 코드리뷰](docs/CODE_REVIEW.md)
- [완료 정의](docs/DEFINITION_OF_DONE.md)
- [GitHub 설정](docs/GITHUB_SETTINGS.md)
- [기여 가이드](CONTRIBUTING.md)
- [보안 정책](SECURITY.md)
