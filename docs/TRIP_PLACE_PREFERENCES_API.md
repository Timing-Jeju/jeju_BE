# 여행 희망·회피 장소 API와 FE 인계

## 인계 상태

- 구현 Issue: #48
- 공개 endpoint: `PUT /api/v1/trips/{tripId}/place-preferences`
- operationId: `tripPlacePreferencesUpdate`
- FE 소스 변경: 없음
- FE용 생성물: `services/spring-api/build/distributions/timing-jeju-frontend-api-client.tgz`
- 생성물은 FE 저장소에 자동 복사하지 않는다. FE 담당자가 현재 mock 교체 시 명시적으로 반영한다.

## 요청

```http
PUT /api/v1/trips/44000000-0000-0000-0000-000000000044/place-preferences HTTP/1.1
Authorization: Bearer <access-token>
If-Match: "trip-current-version"
Content-Type: application/json
```

```json
{
  "items": [
    {
      "placeId": "48000000-0000-0000-0000-000000000010",
      "type": "must_visit",
      "targetDayNo": 2,
      "priority": 90
    },
    {
      "placeId": "48000000-0000-0000-0000-000000000011",
      "type": "avoid",
      "targetDayNo": null,
      "priority": 10
    }
  ]
}
```

`items`는 전체교체 배열이다. 빈 배열은 모든 희망·회피 장소를 삭제한다. 한 장소는 `must_visit` 또는 `avoid` 중 하나만 가질 수 있다. 응답 순서는 `priority DESC, placeId ASC`다. `targetDayNo`는 필수 property이며 전체 여행 범위 또는 `null`이어야 한다. 장소는 현재 사용자가 저장했고 planner 입력에 사용할 수 있는 canonical Tour place UUID여야 한다.

요청은 시간·거리·비용·위험·경로를 받지 않는다. TMAP 원문·geometry, provider payload, 사용자 원문, JWT를 저장하거나 응답하지 않는다.

## 응답과 일정 효과

```json
{
  "tripId": "44000000-0000-0000-0000-000000000044",
  "scheduleEffect": "invalidated",
  "regenerationRequired": true,
  "activeScheduleVersionId": null,
  "tripStatus": "draft",
  "updatedAt": "2026-09-01T03:04:05.123456Z",
  "items": [
    {
      "placeId": "48000000-0000-0000-0000-000000000010",
      "type": "must_visit",
      "targetDayNo": 2,
      "priority": 90
    }
  ]
}
```

성공은 `200`과 새 strong `ETag`를 반환한다. canonical 값이 같으면 기존 `updatedAt`, ETag와 active schedule을 유지한다. 값이 달라지고 active schedule이 있으면 같은 transaction에서 기존 버전을 `superseded`로 전환하고 trip을 `draft`로 되돌린다.

## FE 상태 전이

관심 장소 화면에서 장소 선택을 완료할 때 마지막 trip ETag와 함께 전체 배열을 보낸다. `regenerationRequired=true`이면 기존 `DayReview`를 확정본으로 계속 표시하지 않고 generation 접수 단계로 이동한다. `409 TRIP_VERSION_CONFLICT`이면 최신 trip과 ETag를 다시 읽은 뒤 사용자의 선택을 재적용한다.

주요 오류 분기는 다음과 같다.

- `400 INVALID_REQUEST`: malformed/unknown/null/type/query/If-Match 형식
- `401 AUTHENTICATION_REQUIRED | INVALID_ACCESS_TOKEN`: 인증 실패
- `404 TRIP_NOT_FOUND`: 없거나 다른 사용자의 trip
- `404 PLACE_NOT_FOUND`: 저장하지 않았거나 stale/tombstoned/source-deleted 장소
- `409 TRIP_VERSION_CONFLICT | TRIP_TERMINAL_STATE_CONFLICT`: 동시 수정 또는 종료 여행
- `422 PLACE_PREFERENCE_CONSTRAINT_VIOLATION`: 중복 장소, role, Day, priority 제약
- `503 TRIP_DATA_UNAVAILABLE`: 일시적인 저장소 장애

## TypeScript client 생성

```bash
cd services/spring-api
./gradlew --no-daemon openApiDocs
cd ../..
./scripts/generate_frontend_api_client.sh
```

생성기는 먼저 21개 exact inventory와 canonical schema를 검사하고 고정된 `typescript@6.0.3`, `@hey-api/openapi-ts@0.99.0`을 사용한다. 결과 디렉터리와 압축 파일은 build artifact이며 Git에 넣지 않는다.
