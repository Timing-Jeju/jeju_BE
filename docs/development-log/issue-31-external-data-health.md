# Issue #31 외부 데이터 Actuator·운영 진단 개발 일지

## 범위

- #160의 TourAPI·TAGO·KMA read-only 상태 집계와 #162의 public status-only indicator를 재사용한다.
- private 별도 management port에 operator 상세 Actuator endpoint를 추가한다.
- #40 DEFER와 #41 provider-neutral 계약을 유지해 mobility는 `DISABLED`로 표시한다.
- 공개 API, OpenAPI, DB, importer, TMAP HTTP client는 변경하지 않는다.

## First RED

명령:

```text
cd services/spring-api
./gradlew --no-daemon unitTest \
  --tests 'com.timingjeju.api.global.datahealth.ExternalDataHealthEndpointTest' \
  --tests 'com.timingjeju.api.global.datahealth.OpsJwtValidatorTest' --console=plain
```

결과는 `compileTestJava` 13 errors였다. 상세 endpoint/응답 projection, fallback code, 전체 상태와 operator JWT validator가 존재하지 않는 의도한 실패였다.

## Green

- `/actuator/externaldatahealth` custom Actuator endpoint
- 별도 `management.server.port` 필수와 default-off 구성
- RS256 JWKS, exact `aud=timing-jeju-ops`, `role=operator`, expiry/issuer/clock-skew 검증
- 무토큰·사용자 token 401, operator token 200, application port 상세 endpoint 비노출
- 마지막 실패와 이전 유효 facts 동시 표시
- raw metadata를 담을 수 없는 allowlist projection
- typed DB/data 실패의 raw cause 없는 `DATA_HEALTH_UNAVAILABLE`
- mobility DEFER `DISABLED`, fallback `대체_미사용`

focused unit/configuration 및 실제 random application/management 2-port 통합 테스트가 성공했다.

## Refactor 검증

- 공개 health가 별도 management port에서도 무토큰 status-only를 유지하는 회귀를 추가했다.
- focused 전체 data-health와 Architecture 검사가 성공했다.
- Spring `clean check`: 13분 5초, 전체 테스트·JaCoCo·OpenAPI 성공
- 비밀정보 전체 검사와 `git diff --check`: 성공

남은 단계는 commit/push 후 exact-SHA root quality Gate·Docker smoke와 독립 Reviewer 승인이다.
