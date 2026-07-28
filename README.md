# Timing Jeju 백엔드 모노레포

제주 여행 동선과 관광·교통 데이터를 다루는 Timing Jeju의 백엔드 모노레포입니다. 공통 계약과 데이터는 루트에서 관리하고 Spring Boot 공개 API와 FastAPI MCP는 독립된 서비스 디렉터리와 컨테이너로 관리합니다.

## 저장소 구조

```text
.
├── services/
│   ├── spring-api/       # 공개 API, DB·외부 API, MCP 조립과 결과 저장
│   └── fastapi-mcp/      # 내부 계산·AI MCP, 구조 중립적인 Python 도구 기반
├── docs/                 # 공통 아키텍처와 서비스 간 계약
├── db/                   # 공통 PostgreSQL/PostGIS 스키마
├── fixtures/             # 공통 검증 fixture
├── scripts/              # 루트 통합 품질 게이트와 자동화
├── compose.yml           # 서비스 통합 실행
└── AGENTS.md             # 공통 개발·Git·보안 규칙
```

두 서비스는 같은 저장소에서 계약을 함께 변경할 수 있지만 런타임, 의존성, 테스트와 배포 경계는 분리합니다. FastAPI는 Python과 품질 도구만 초기화했으며 애플리케이션 패키지 구조와 예시 계산 코드는 만들지 않았습니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle 9.5.1 Wrapper
- FastAPI 0.139, MCP Python SDK 1.x, uv
- PostgreSQL 16 + PostGIS 3.4
- JUnit 5, AssertJ, Mockito, Spring Test, ArchUnit, JaCoCo
- Docker / Docker Compose

## 요구 환경

- JDK 21
- Docker Engine과 Docker Compose
- Python 3.12와 uv 0.11.32 이상
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

FastAPI 최소 환경만 확인하려면 다음을 실행합니다.

```bash
./scripts/quality-gate.sh --scope fastapi
```

루트 `quality-gate`는 브랜치·비밀정보·모노레포 구조·Spring 검사·FastAPI 검사·Docker smoke test를 순서대로 검증합니다. 성공 기록은 커밋되지 않는 `.codex/state/quality-gates/`에 저장됩니다.

GitHub Actions의 `백엔드 모노레포 CI`는 공통, Spring, FastAPI와 서비스 계약 Job을 분리합니다. 공통 검사는 항상 실행하고 서비스별 검사는 변경 경로에 따라 선택하며, 계약 변경은 양쪽 서비스와 계약 검사를 모두 실행합니다. 마지막 `quality-gate` Job이 실행 대상의 성공 여부를 하나의 필수 체크로 집계합니다.

## Docker 실행

```bash
docker compose -f compose.yml up --build
./scripts/docker-smoke-test.sh
```

기존 `docker-compose.yml`은 PostgreSQL/PostGIS 단독 개발용으로 유지합니다. `compose.yml`은 애플리케이션과 DB를 함께 실행하고, `compose.test.yml`은 격리된 smoke test에 사용합니다.

현재 Compose에는 실행 엔트리포인트가 있는 Spring API만 연결되어 있습니다. FastAPI의 패키지와 엔트리포인트를 첫 기능 Issue에서 정하면 private network 서비스와 `/health/live`, `/health/ready` 검증을 추가합니다.

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
