# Timing Jeju Spring API

Timing Jeju의 공개 REST API, 인증·인가, 데이터베이스와 외부 API 연동, MCP 입력 조립과 결과 검증·저장을 담당하는 Spring Boot 서비스입니다.

## 실행

```bash
./gradlew bootRun
```

## 검사

```bash
./gradlew clean check
./gradlew unitTest sliceTest integrationTest architectureTest
```

Docker와 저장소 전체 검증은 저장소 루트에서 `./scripts/quality-gate.sh`를 실행합니다.
