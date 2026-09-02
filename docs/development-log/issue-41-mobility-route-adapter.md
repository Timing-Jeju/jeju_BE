# Issue #41 이동 경로 provider-neutral adapter·cache 개발 기록

## 상태

- 기준: `origin/develop` `c2906f18a7303b1f959df435e25be05de935e66b`
- 브랜치: `feat/41-mobility-route-adapter`
- #23과 #40은 CLOSED이며 #40의 최종 판정은 `DEFER`다.
- Spring TMAP client, 공개 Controller, DB migration과 route snapshot write는 추가하지 않는다.

## First RED

명령:

```bash
cd services/spring-api
./gradlew --no-daemon unitTest --tests 'com.timingjeju.api.application.mobility.*'
```

결과는 `compileTestJava`의 79 errors였다. provider-neutral request/measurement/fact,
`MobilityRouteProvider`, privacy request hasher와 memory cache service가 없어서 mode matrix,
fresh hit, expiry equality, 보행 fallback, 동시 single-flight 테스트가 컴파일되지 않았다.
같은 증거를 Issue #41 comment `5502225190`에 시간순으로 기록했다.

## 최소 GREEN과 Refactor

- application 계층에 네 mode의 provider-neutral request/measurement/fact를 추가했다.
- duration 구성요소 합계, nullable fare, 좌표·거리·비용·TTL 상한을 fail-closed 검증한다.
- source ID와 좌표·시각·mode를 길이 구분 SHA-256 key로 만들고 원문은 key에 노출하지 않는다.
- fresh hit와 프로세스 내 동시 single-flight를 구현하고 expiry equality는 만료로 처리한다.
- 복구 가능한 보행 실패에만 주입된 보수 추정을 허용하고 차량·택시·대중교통은 추정하지 않는다.
- provider runtime message/cause를 버리고 안정 code로 변환한다.
- application mobility package가 Spring, JDBC, global adapter에 의존하지 않도록 ArchUnit으로 고정한다.
- Spring TMAP bean과 JDBC persistence는 만들지 않고 기존 기본 비활성·비저장 경계를 유지한다.
- Refactor self-review에서 provider null을 generic unavailable로 분류해 보행 fallback하는 빈틈을
  발견했다. 단일 테스트가 “throwable이 없음”으로 RED가 된 뒤 null을
  `INVALID_PROVIDER_RESPONSE`로 분류하고 fallback을 금지해 다시 Green으로 만들었다.

## 검증 예정

- focused unit와 Architecture
- Spring `clean check`: Refactor 최종 상태에서 13분 7초, `BUILD SUCCESSFUL`
- 루트 전체 quality Gate와 Docker smoke
- secret scan과 `git diff --check`
- exact HEAD 독립 Reviewer
