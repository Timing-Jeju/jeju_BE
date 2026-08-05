# Issue #16 커서 페이지네이션 공통 모듈

## 공개 API 변경 근거

이 작업은 후속 공개 목록 API가 공통 계약에 의존할 수 있도록 Spring 내부 application/adapter 커서 페이지네이션 계약만 추가한다. 현재 Issue 범위에는 실제 `/api/v1/**` Controller 추가가 없으므로 OpenAPI 경로와 DTO 산출물을 변경하지 않는다. 공통 오류 응답은 기존 Problem Details registry에 `CURSOR_INVALID`를 추가해 공개 API가 커서를 적용하는 시점에도 같은 오류 envelope를 사용한다.

## 정렬 타입 계약

`CursorPosition`은 cursor 직렬화를 위한 문자열 값만 소유하며 값의 숫자·문자열·시간 의미를 추측하지 않는다. `CursorKeysetPaginator.page` 호출자는 sort 값과 유일 tie-breaker 각각의 오름차순 `Comparator<String>`를 반드시 제공한다. paginator는 두 comparator를 stable keyset 순서로 합성하고 `CursorSort` 방향을 적용하며, 목록 정렬과 cursor 이후 경계 판정에 같은 합성 comparator를 사용한다.

숫자 점수는 호출자가 `Comparator.comparingInt(Integer::parseInt)`, ISO 시간은 `Comparator.comparing(Instant::parse)`, 문자열과 ID는 `Comparator.naturalOrder()`처럼 의미를 명시한다. paginator가 문자열 형태를 보고 숫자로 추측 변환하는 기본 동작은 제공하지 않는다.
