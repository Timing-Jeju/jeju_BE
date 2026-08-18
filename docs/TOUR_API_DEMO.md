# TourAPI 데모 수집 시연 가이드

## 목적
- 8월 18일 데모에서 `TourAPI` 리스트/상세(설명/반복정보/이미지)를 실제 외부 수집 후 DB 저장이 되는지 시연한다.
- 실패가 일부 발생해도 리스트 수집이 끝난 범위에서 결과를 부분 성공으로 받아볼 수 있게 설계된 동작을 확인한다.

## 실행 준비
1. 로컬 서버 실행 전 환경
   - `TOUR_API_ENABLED=true`
   - `TOUR_API_API_KEY=<your-key>`
   - `API_PORT=18080` (예: in-app-browser 주소 `http://localhost:18080/swagger-ui/index.html`)

2. 서버 상태 확인
   - Swagger / health 확인: `GET http://localhost:{API_PORT}/swagger-ui/index.html`

## 수집 실행
- 수집 시작: `POST /api/v1/demo/imports/tour-api`

요청 바디 예시:
```json
{
  "areaCodes": ["39"],
  "contentTypes": ["12", "32", "39"],
  "maxPages": 4,
  "maxPlaceCount": 3,
  "contentType": null
}
```

주의:
- `maxPages`는 4로 고정해도 되지만, API의 페이지 단위는 내부 보호값으로 더 넓은 전체 조회(`MAX_PAGES`)를 유지하고 있습니다.
- 실제 운영 데모에서는 회의 중 확인 가능한 범위를 위해 `maxPlaceCount`를 작게 설정합니다.

## 시연용 확인 경로
- 최신 저장 상태 JSON: `GET /api/v1/demo/storage`
- 시각용(테이블) 저장 뷰: `GET /api/v1/demo/storage/view`

## 응답/표시 정책 (중요)
- 리스트 조회/정규화는 실패하면 `POST /imports/tour-api`가 실패합니다.
- 목록 수집이 끝난 뒤 `common/intro/info/image` 단계는 개별 후보별로 분리되어 실행되며, 일부 실패해도 전체 import를 즉시 중단하지 않고 **부분 성공(HTTP 200)** 응답을 반환합니다.
- 시연 화면/로그에는 외부 API 쿼리 파라미터, API key, raw 응답/에러 본문을 노출하지 않습니다.
- `storage/view`는 `tour_places`, `place_details`, `place_detail_items`, `place_images`, `data_import_runs`, `external_api_snapshots` 기준으로 정합적으로 보여줍니다.

## 2026-08-18 실사례 기준 결과 스냅샷(요약)
- 페이지 수집: 22페이지 처리
- 장소 저장: `tour_places = 2150`
- 상세 저장: `place_details = 3`
- 반복정보: `items = 6`
- 이미지: `images = 24`
- 스냅샷: `snapshots = 34`
- 계보: `provenance = 4339`
- 실행 러닝: area list(부분), common 3, intro 3, info 2 fail 1, image 2 fail 1

예: `POST` 결과는 200(부분성공)으로 노출되며, 시연 기준으로 성공/실패 카운트를 모두 확인할 수 있습니다.

## 회의 데모 체크리스트
- 수집 2개 이상 샘플 카테고리(12/32/39)에서
  - 장소 저장
  - 장소 설명(`place_details`)
  - 반복정보(`place_detail_items`)
  - 이미지 썸네일/원본(`place_images`)
  을 실제로 확인
- 실패 후보가 있어도 나머지 후보가 진행되는지 확인
- `/api/v1/demo/storage/view`에서 동일 장소의 단계별 상태와 타임라인 점검
