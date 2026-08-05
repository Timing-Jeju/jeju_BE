# Issue #16 커서 페이지네이션 공통 모듈

## 공개 API 변경 근거

이 작업은 후속 공개 목록 API가 공통 계약에 의존할 수 있도록 Spring 내부 application/adapter 커서 페이지네이션 계약만 추가한다. 현재 Issue 범위에는 실제 `/api/v1/**` Controller 추가가 없으므로 OpenAPI 경로와 DTO 산출물을 변경하지 않는다. 공통 오류 응답은 기존 Problem Details registry에 `CURSOR_INVALID`를 추가해 공개 API가 커서를 적용하는 시점에도 같은 오류 envelope를 사용한다.
