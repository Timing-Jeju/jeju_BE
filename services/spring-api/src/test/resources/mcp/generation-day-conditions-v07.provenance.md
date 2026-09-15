# 생성 조건 synthetic fixture

- 생성 주체: `GenerationTripInputTest`의 고정 Trip 입력과 `GenerationMcpDayConditions`.
- 검증 대상: BE adapter 출력 전체와 JSON tree가 같은지, AI Pydantic v0.7 입력 모델이 수용하는지.
- 식별자·이름·날짜는 테스트 전용 합성 값이다. 실제 TourAPI의 해당 contentid가 기재된 이름의
  장소라는 주장이나 승인된 운영 데이터가 아니다. 이 fixture로 외부 API를 호출하지 않는다.
- 승인 장소 조회/계보는 별도의 `GenerationIntakeIntegrationTest`에서 검증한다.
- 첫날 당일 여행의 하루 조건만 담는다. MCP `requestId`/`inputHash` envelope, 이전 Day의
  적용 이력 및 실제 생성 결과 검증을 대체하지 않는다.
- MCP 공개 JSON Schema는 복제·수정하지 않는다. AI 저장소 HEAD 8d3e10c 및 #19의 45f585a 모델의
  `RecommendDayTripsInput.model_validate_json`으로 호환성을 확인했다.
