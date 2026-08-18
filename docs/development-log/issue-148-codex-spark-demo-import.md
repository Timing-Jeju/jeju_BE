# Issue #148 데모 수집/저장 시연 준비 기록

## 범위
- 오늘 회의 데모용: `/api/v1/demo/imports/tour-api` 및 저장소 조회 API 동작 확인용 백엔드 라인업
- 목록 1회 수집 후 후보(`12,32,39`) 상세·설명·이미지 수집 저장까지 포함
- 저장 뷰(`/api/v1/demo/storage`, `/api/v1/demo/storage/view`)에 장소/상세/항목/이미지/provenance 안전 노출
- 외부 API 요청 경로/쿼리 안전성 및 아키텍처 위반 해결
- 운영성: compose API 노출 포트 파라미터화

## Red → Green

- 초기 실패/리스크: `PlaceListImportCommand` 입력 검증, `ExternalApiRequest` 경로/쿼리 게이트, detail 공통/intro 업서트/리플레이 처리, sweep 통계, 후보 탐색 스코프, 상세/이미지 저장 view 반영에서 반복적인 적합성 누락 및 테스트 불일치가 누적되어 있었음.
- `./gradlew architectureTest` 에서 `DemoImportController`가 `..service..` 패키지를 직접 의존하지 않는 위반 1건이 재현됨.
- `./gradlew test --tests ...` 중심으로 핵심 suite를 TDD로 보완하고, 이후 integration reader test, storage view render test, detail/item/image import 테스트를 갱신해 Green으로 정리.
- 아키텍처 위반은 `domain.demo.service.DemoImportService` 어댑터 추가로 해결.
- compose 운영성은 `compose.yml`의 API 포트를 `${API_PORT:-8080}`로 파라미터화하고 `.env.example`에 `API_PORT` 추가.

## 실행 근거

- `./gradlew test --tests "com.timingjeju.api.application.tourapi.place.PlaceListImportServiceTest" --tests "com.timingjeju.api.application.demo.DemoImportServiceTest" --tests "com.timingjeju.api.application.demo.DemoStorageViewTest" --tests "com.timingjeju.api.domain.demo.controller.DemoImportControllerTest" --tests "com.timingjeju.api.global.externalapi.ExternalApiExecutorTest" --tests "com.timingjeju.api.global.demo.JdbcDemoStorageReaderIntegrationTest"`
- `./gradlew architectureTest`

두 항목 모두 Green.

## 운영 반영
- compose.yml: `api` 서비스 포트 `"${API_PORT:-8080}:8080"`
- .env.example: `API_PORT=8080` 추가
- 로컬 실행 시 환경 파일에서 `API_PORT=18080` 지정으로 즉시 적용 가능
