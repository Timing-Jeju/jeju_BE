# #226 OpenAPI 구현 준비 상태 경계

## 문제와 변경

미구현 날씨 selector 계약을 현행 좌표 Controller에 투영하면 API 문서 생성이 실패한다.
implementation readiness를 별도 정책으로 해석하여 ready 도메인만 canonical schema를
투영한다. not-ready는 runtime-derived schema를 유지하며 상태 누락·중복·비정상 근거는
구성 오류로 실패한다. ready 성공 응답 누락도 오류로 처리한다.

실제 catalog를 승격하지 않는다. 기존 canonical 투영 assertion은 테스트 전용 ready
fixture로 유지하고, 실제 catalog HTTP 검사와 미래 selector HTTP 검사를 별도로 둔다.
Python은 canonical schema 비교만 분기하고 endpoint·인증·status·Problem 검증을 유지한다.
투영 제거로 드러난 DTO 문서 누락은 실제 setter의 입력 형식과 nullable, raw byte body의
실제 요청 DTO, boolean const를 명시해 수정했다. Controller의 요청 처리 동작은 바꾸지 않았다.

## RED → GREEN → Refactor

- RED: `unitTest --tests '*CanonicalProjectionReadinessTest' --tests '*FrontendOpenApiReadinessTest'`:
  readiness 정책 클래스 부재로 컴파일 실패 (`/tmp/jeju-226-red.log`).
- RED: ready 성공 응답 누락 시 예외가 발생하지 않음
  (`/tmp/jeju-226-response-red.log`).
- RED: Python not-ready 검증 분기와 누락 readiness 검증 실패
  (`/tmp/jeju-226-python-red.log`).
- RED: 실제 산출물 검증에서 nullable/byte body/boolean enum 오류 확인.
- GREEN: readiness 단위 7개, OpenAPI slice 22개 통과
  (`/tmp/jeju-226-final-targeted.log`).
- GREEN: Python 문서 검사 단위 33개 통과 (`/tmp/jeju-226-python-green.log`).
- GREEN: 실제 `openApiDocs` 생성과 `validate_openapi_frontend_readiness.py --mode 33`
  37개 endpoint 검사 통과 (`/tmp/jeju-226-validation.log`).
- Refactor: domain readiness 해석을 독립 정책으로 분리하고 테스트 resource 주입을
  생성자 경계에 둠. 실제 catalog, Notion/Figma 연결 상태는 변경하지 않음.

## 검증 범위

전체 quality-gate와 Docker 결과는 동일 HEAD의 공식 상태 및 PR에 기록한다.
테스트 전용 ready fixture는 release readiness의 근거가 아니다.
#220, #221, #222의 위치 비수집 계약/런타임 전환은 이 변경에 포함하지 않는다.
운영 DB 적용·배포·외부 provider 호출은 수행하지 않는다.
