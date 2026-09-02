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

## 독립 Review 1차와 remediation

검토 HEAD `ba5e3b73c776b4675859ee477c1b21dfdf3c5912`는 공식 Gate를 통과했지만
Reviewer가 MAJOR 3건으로 `CHANGES_REQUESTED`를 기록했다.

1. 호출자가 없는 수동 `cleanup()`만으로는 고유 key의 만료 metric을 자동 제거하지 못했다.
2. provider 내부의 잘못된 거리·duration·fare·TTL 생성 오류를 일시 장애로 오분류해 WALK
   fallback을 허용했다.
3. `ESTIMATED_WALK_TIME` fact가 non-WALK mode에서도 생성될 수 있었다.

### Review RED

- 자동 eviction·lifecycle 계약 테스트는 `AutoCloseable`과 `cacheSize()` 부재로 컴파일 RED였다.
- 최소 scheduler 구조를 추가한 뒤 malformed 4종과 non-WALK reason 테스트가 각각
  `Expecting code to raise a throwable`로 assertion RED가 됐다.

### Review GREEN

- 가장 이른 expiry 하나만 예약하는 단일 daemon scheduler를 두고 만료 시 자동 sweep 후 다음
  expiry를 재예약한다. `close()`는 예약 작업을 취소하고 cache를 비운다.
- provider normalization 과정의 `IllegalArgumentException`은 원문/cause 없이
  `INVALID_PROVIDER_RESPONSE`로 변환하며 WALK fallback을 허용하지 않는다.
- fact 생성자가 `ESTIMATED_WALK_TIME`과 `WALK` mode 조합을 직접 강제한다.
- focused mobility unit 16건과 Architecture test가 성공했다.
- remediation 최종 Spring `./gradlew --no-daemon clean check`는 12분 56초에
  `BUILD SUCCESSFUL`로 종료됐다.

## 검증

- focused unit와 Architecture: 성공
- Spring `clean check`: Refactor 최종 상태에서 13분 7초, `BUILD SUCCESSFUL`
- 최초 exact HEAD 루트 quality Gate와 Docker smoke: `SUCCESS`
- review remediation 최종 상태에서 전체 `clean check`, root Gate, secret scan,
  `git diff --check`와 독립 재검토를 다시 실행한다.

## 독립 Review 2차와 remediation

검토 HEAD `d1b66c43b95b3dc732a503688ae46f16fe309cf9`에서 정상 자동 eviction과
WALK-only reason은 승인됐지만 MAJOR 2건이 남았다.

1. provider의 null mode·duration·validFor가 `NullPointerException`으로 빠져 WALK fallback을
   허용했다.
2. `close()` 후 신규 get과 진행 중 load의 cache 재삽입을 막지 못했다.

### Review 2 RED/GREEN

- null 필드 3종은 기존 구현에서 예외 없이 fallback되어 assertion RED였다.
- close 후 get과 provider fetch 중 close 동시성도 예외 없이 완료되어 assertion RED였다.
- null 필드 생성 오류를 cause 없는 `INVALID_PROVIDER_RESPONSE`로 닫고 estimator 0회를 고정했다.
- 비복구 `CACHE_CLOSED` code를 추가하고 get 시작과 loaded fact의 cache commit을 lifecycle
  lock으로 선형화했다. close 중인 leader/follower는 모두 같은 종료 예외로 완료되고 cache는
  0을 유지한다.
- focused mobility unit 18건과 Architecture test가 성공했다.
- review 2 remediation 최종 Spring `./gradlew --no-daemon clean check`는 12분 44초에
  `BUILD SUCCESSFUL`로 종료됐다.
