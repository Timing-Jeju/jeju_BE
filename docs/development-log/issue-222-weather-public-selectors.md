# Issue 222 날씨 위치 비수집

## 구현과 검증 순서

최신 develop b0c490f에서 독립 브랜치를 준비했다. #220 계약 및 #221 선행 작업의 최종
병합 뒤 최신 base에서 전체 품질 gate와 최종 PR을 검증한다.

- RED: WeatherForecastQuery record에 lat/lng가 남아 있음을 검출.
- GREEN: regionCode/placeId/tripItemId exactly-one과 실제 제주 정시만 허용.
- Controller는 unknown/중복 parameter를 거부하고 검증된 JWT sub만 service에 전달한다.
- 계획 항목은 로그인 필수, trip.user_id와 item.place_id만 조회한다. 소유자 불일치/미존재는404.
- 공개 장소의 source_deleted_at/tombstoned_at/content_id를 검사하고 facts JSON 좌표는 사용하지 않는다.
- KMA 격자 변환, horizon/base/fallback/freshness는 기존 구현을 유지한다.
- HTTP malformed 입력의 값이 Problem과 캡처 로그에 반사되지 않음을 검사한다.

## 지역 대표 격자 공급과 출처

독립 advisory에서 관광지 nearest_place_id 및 nx/ny 정렬로 지역 대표 격자를 고르는
방식의 근거가 부족함을 확인했다. 해당 로직을 제거하고 공식 행정구역 행의 grid를 사용한다.

- 원천: [기상청 API허브 동네예보](https://apihub.kma.go.kr/apiList.do?seqApi=10)의
  [동네예보지점좌표(위경도)_260701.xlsx](https://apihub.kma.go.kr/getAttachFile.do?fileName=%EB%8F%99%EB%84%A4%EC%98%88%EB%B3%B4%EC%A7%80%EC%A0%90%EC%A2%8C%ED%91%9C%28%EC%9C%84%EA%B2%BD%EB%8F%84%29_260701.xlsx).
- 2026-09-08 원본 실물 다운로드 SHA256:
  `746b5e5be10430106abccc795a16c16d0f1fd0f081e8ab2765d0adc983a003c1`.
- 추출: sheet1 B열 행정구역코드 5011000000/5013000000/5013025900을 정확히 선택,
  C/D/E 행정명과 F/G grid를 추출했다. 대표 위경도 자체는 projection에서 제외했다.
- 앱의 명시 선택 code는 각각 jeju-si/seogwipo-si/seongsan으로 대응한다.
- 패키지 공급: `src/main/resources/weather/region-grids-260701.csv`, SHA256
  `1dddd675d2e37c72101e9526e213c6354e19cfa21cace62c6924114126585151`.
- 이 세 지역만 지원한다. 새 지역은 검증한 공식 행을 추가하는 별도 versioned 변경으로 확장한다.
- catalog는 시작 시 읽고 중복/비정규/범위 오류를 거부한다. 관광지·사용자 위치로
  대표 grid를 추론하거나 요청 시 외부 서비스를 호출하지 않는다.
- CSV는 지역→grid 공급이며 예보 자료 자체를 만들지 않는다. 해당 grid와 예보는 기존
  KMA importer가 적재한 저장 자료가 필요하다. 미적재 grid는422, 예보 부족은 기존503이다.
- 공식 공공데이터포털의 [KMA 단기예보 이용조건](https://www.data.go.kr/data/15084084/openapi.do)은
  공공저작물 출처표시 제1유형으로 표시된다. 이 변경은 출처를 보존한 최소 공개 grid projection이다.

## 미완료

선택자·서비스·지역 catalog 단위, Controller slice와 실제 PostgreSQL repository 검사가 통과했다.
전체 품질 gate, 최신 canonical/OpenAPI 정렬 및 독립 최종 리뷰는 진행 중이다.
실제 승인 provider 호출, native staging, 운영 DB 적용·배포는 수행하지 않았다.

## #220 병합 후 OpenAPI 정렬

- develop f7fc751의 위치 비수집 v2 계약을 병합했다. #221 병합 후 최신 base 최종 검증은 별도 진행한다.
- 실제 Swagger query는 regionCode/placeId/tripItemId/dateTime 네 개로 고정하고 contractVersion 2.0.0, 400 INVALID_WEATHER_SELECTOR, 404 WEATHER_REFERENCE_NOT_FOUND를 검사한다.
- runtime manifest 회귀 RED: `/tmp/jeju-222-manifest-red.log` (기존 404 누락); GREEN: `/tmp/jeju-222-manifest-green.log` 6개 검사 성공.
- Swagger slice GREEN: `/tmp/jeju-222-openapi-green.log`, BUILD SUCCESSFUL. 최초 테스트 경로 version은 실제 DTO contractVersion으로 바로잡았다.
- Notion/Figma 실제 readback은 기존 1.0.0 GPS 계약이다. Notion v2 본문 갱신은 무료 블록 한도로 거부돼 `issue-222-notion-v2-pending.md`에 적용안을 보존했다. 버전 속성은 1.0.0으로 복원, Draft 상태를 재조회로 확인했다. v2 문서 readiness는 통과 처리하지 않았다.
