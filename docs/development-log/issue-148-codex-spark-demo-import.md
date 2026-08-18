# Issue #148 데모 수집/저장 시연 준비 기록

## 범위
- 8월 회의 데모용: `/api/v1/demo/imports/tour-api`로 외부 리스트/상세 수집 및 DB 저장 검증
- 후보 수집(`12/32/39`)의 공통/소개/반복정보/이미지 파이프라인 가용화
- 저장 뷰(`/api/v1/demo/storage`, `/api/v1/demo/storage/view`)에 장소·상세·항목·이미지 및 provenance 안전 노출
- 외부 API 요청 경로/파라미터 안전성, 아키텍처 경계, 운영성(포트 파라미터화) 정리
- 데모에서 부분 실패 허용 동작 정합성 확보

## Red → Green

- 초기 실패/리스크: `PlaceListImportCommand` 입력 검증, `ExternalApiRequest` 경로/쿼리 게이트, detail 공통/intro 업서트/리플레이 처리, sweep 통계, 후보 탐색 스코프, 저장 view 반영에서 반복적인 적합성 누락 및 테스트 불일치가 누적되어 있었음.
- 공통 실패(계약 파라미터 누락/불일치), 중복 키 충돌, 부분 페이지(`numOfRows`) 처리, 시간 정밀도(`timestamp`) 비교, URL 검증, 스테이지 중간 실패 후 이어서 진행 등의 실제 이슈를 TDD로 정리함.
- 아키텍처 위반은 `domain.demo.service.DemoImportService` 어댑터 추가 및 계약 분리로 정리.
- `./gradlew test --tests ...`, 통합 테스트, storage view render 테스트로 핵심 케이스를 Red→Green 수행했으며, 점검이 필요한 핵심 항목은 실제 PostgreSQL 연동 테스트에서 확인 후 패치함.
- 데모 실행 중 partial 실패가 전체 중단되는 문제를 수정해, 후보별 3개 단계(info/image를 포함한 상세 단계)가 개별 실패해도 같은 후보/다음 후보 진행이 유지되도록 고침.
- `compose.yml`의 API 노출 포트를 `${API_PORT:-8080}:8080`로 고정값 의존 제거, `.env.example`에 `API_PORT=8080` 문서화.

## 2026-08-18 실데모 근거

- 실제 실운영 점검에서 `POST /api/v1/demo/imports/tour-api`는 리스트 전체 수집은 정상 진행되고, 일부 후보 상세 단계에서 2건 실패해도 전체는 부분 성공(HTTP 200)로 종료됨.
- 수집/저장 요약:
  - `tour_places = 2150`
  - `place_details = 3`
  - `place_detail_items = 6`
  - `place_images = 24`
  - `snapshots = 34`
  - `provenance = 4339`
- 런 상태: area list run 1(부분 완수), 공통/소개 3개 모두 성공, 반복정보 2성공 1실패, 이미지 2성공 1실패, running 0 / terminal 13
- 실패 원인은 개별 provider 응답 오류/누락/특정 단계 반영 제외였고, 공통은 정상 수집된 데이터로 저장이 이루어졌음.

## 운영 반영
- `compose.yml`: API 포트 `"${API_PORT:-8080}:8080"`
- `.env.example`: `API_PORT=8080`
- 로컬/데모: `API_PORT=18080`로 지정하면 in-app browser 환경(`http://localhost:18080`)에서 바로 확인 가능
- 시연용 문서: `docs/TOUR_API_DEMO.md` 추가
- `/api/v1/demo/storage/view`는 raw query/key/error payload를 노출하지 않고, `tour_places`, `place_details`, `place_detail_items`, `place_images`를 조합해 상태를 보여줌

## 추가로 검증/참고한 항목
- `./gradlew architectureTest`
- `./gradlew test --tests "com.timingjeju.api.application.tourapi.place.PlaceListImportServiceTest" --tests "com.timingjeju.api.application.demo.DemoImportServiceTest" --tests "com.timingjeju.api.application.demo.DemoStorageViewTest" --tests "com.timingjeju.api.domain.demo.controller.DemoImportControllerTest" --tests "com.timingjeju.api.global.externalapi.ExternalApiRequestTest" --tests "com.timingjeju.api.global.demo.JdbcDemoStorageReaderIntegrationTest"`
- `./gradlew test --tests "com.timingjeju.api.domain.tourapi.PlaceDetailImportServiceTest" --tests "com.timingjeju.api.domain.tourapi.PlaceImageImportServiceTest" --tests "com.timingjeju.api.domain.tourapi.DetailItemImportServiceTest"`
