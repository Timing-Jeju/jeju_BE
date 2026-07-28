# 아키텍처

## 모노레포 구조

Timing Jeju 백엔드는 Spring Boot 공개 API와 FastAPI MCP를 한 저장소에서 관리합니다. 공통 계약·DB·fixture·개발 정책은 루트에 두고 실행 코드와 언어별 도구는 서비스 디렉터리에 격리합니다.

```text
.
├── services
│   ├── spring-api
│   │   ├── src
│   │   ├── build.gradle
│   │   ├── gradle
│   │   └── Dockerfile
│   └── fastapi-mcp
│       ├── pyproject.toml
│       ├── uv.lock
│       ├── scripts/quality-gate.sh
│       ├── README.md
│       └── AGENTS.md
├── docs
├── db
├── fixtures
├── scripts
├── compose.yml
└── AGENTS.md
```

FastAPI MCP는 Python 3.12, 의존성 잠금과 품질 도구만 초기화합니다. 애플리케이션 패키지, 테스트 디렉터리, 실행 엔트리포인트는 첫 기능 Issue의 개발자가 TDD 시나리오와 함께 결정하며 현재 단계에서는 가짜 구현을 만들지 않습니다.

## Spring API 내부 구조

Spring API는 서비스 내부에서 하나의 배포 단위를 유지하는 모놀리식 애플리케이션입니다. 최상위 기술 계층별 분리 대신 `domain/{도메인}` 아래에 관련 코드를 함께 둡니다.

```text
com.timingjeju.api
├── TimingJejuApiApplication.java
├── domain
│   └── {domain}
│       ├── controller
│       ├── service
│       ├── repository
│       ├── entity
│       ├── dto/request
│       ├── dto/response
│       ├── mapper
│       └── exception
└── global
    ├── config
    ├── error
    ├── response
    ├── security
    ├── logging
    └── util
```

실제 요구사항이 생기기 전에는 예시용 Member/Auth 도메인이나 가짜 Entity를 만들지 않습니다.

## 의존성 원칙

- 호출 흐름은 `controller → service → repository`입니다.
- Controller는 Repository를 직접 호출하거나 비즈니스 로직을 소유하지 않습니다.
- Entity를 API 응답으로 직접 반환하지 않고 Request/Response DTO를 분리합니다.
- 트랜잭션 경계는 Service에 두고 읽기 전용과 쓰기를 구분합니다.
- 특정 도메인 전용 코드는 `global`로 옮기지 않습니다.
- 다른 도메인의 Repository를 직접 호출하지 않고 공개 Service, Facade 또는 명시적 application 경계를 둡니다.

## ArchUnit 규칙

`services/spring-api`의 `ArchitectureTest`는 Controller의 Repository 직접 의존 금지, Controller의 Service 경유, 도메인 간 순환 의존 금지, Domain의 Global 내부 구현 의존 제한, MVC 계층 이름 규칙을 검사합니다. 아직 도메인이 없는 초기 상태에서는 빈 규칙을 허용하지만 새 클래스가 추가되는 즉시 동일 규칙이 적용됩니다.

## 서비스 간 경계

- 외부 공개 `/api/v1/**`, 사용자 인증·인가, DB와 외부 API는 Spring API가 담당합니다.
- FastAPI MCP는 private network의 `/mcp`로만 호출합니다.
- Spring은 정규화된 facts를 전달하고 FastAPI는 계산 결과를 `structuredContent`로 반환합니다.
- FastAPI는 DB·외부 API·사용자 JWT에 직접 접근하지 않습니다.
- 두 서비스가 함께 바뀌는 계약은 루트 `docs/designs`와 contract test에서 먼저 변경합니다.

## CI 경계

- 공통 정책과 저장소 자동화 검사는 모든 변경에서 실행합니다.
- `services/spring-api` 변경은 Spring 검사와 Docker Health Check를 실행합니다.
- `services/fastapi-mcp` 변경은 uv 잠금, Ruff, mypy와 pytest 검사를 실행합니다.
- 서비스 간 계약 변경은 Spring, FastAPI와 계약 검사를 모두 실행합니다.
- 문서만 변경하고 서비스 계약을 건드리지 않으면 무거운 서비스 검사를 생략합니다.
- 각 Job은 독립적으로 실행되지만 최종 `quality-gate`가 결과를 하나로 집계합니다.
