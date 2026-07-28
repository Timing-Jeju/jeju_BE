# Timing Jeju API

제주 여행 동선과 관광·교통 데이터를 다루는 Timing Jeju의 백엔드 API 저장소입니다. 현재 단계에서는 비즈니스 도메인을 임의로 만들지 않고, 도메인 중심 개발을 위한 Spring Boot 기반과 협업 품질 게이트를 제공합니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle 9.5.1 Wrapper
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
./gradlew bootRun
```

Windows PowerShell에서는 다음을 실행합니다.

```powershell
Copy-Item .env.example .env
./scripts/install-git-hooks.ps1
docker compose -f docker-compose.yml up -d
./gradlew.bat bootRun
```

애플리케이션 상태는 `http://localhost:8080/actuator/health`에서 확인합니다. `.env`의 예시 비밀번호는 로컬 전용이며 실제 운영 비밀값을 저장소에 넣지 않습니다.

## 테스트와 품질 게이트

```bash
./gradlew clean check
./gradlew unitTest sliceTest integrationTest architectureTest
./scripts/quality-gate.sh
```

`quality-gate`는 브랜치·비밀정보·포맷·테스트·커버리지·빌드·Docker smoke test를 순서대로 검증합니다. 성공 기록은 커밋되지 않는 `.codex/state/quality-gates/`에 저장됩니다.

## Docker 실행

```bash
docker compose -f compose.yml up --build
./scripts/docker-smoke-test.sh
```

기존 `docker-compose.yml`은 PostgreSQL/PostGIS 단독 개발용으로 유지합니다. `compose.yml`은 애플리케이션과 DB를 함께 실행하고, `compose.test.yml`은 격리된 smoke test에 사용합니다.

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
